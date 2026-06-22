# Deep Research — 티켓팅 동시성 제어 시스템 코드 분석

> 작성일: 2026-06-22
> 대상: `com.example.ticketing` 전체 소스 코드 (main + test + load-test)
> 목적: 락 전략별(V1~V6) + 대기열 모듈(WaitingRoom)의 **실제 코드 동작**을 한 줄 한 줄 추적해 정리한다. CLAUDE.md의 설명이 아니라 **현재 소스가 진짜로 무엇을 하는지**를 기록한다.

---

## 0. 한눈에 보는 전체 구조

이 프로젝트는 "동시 다발 예매 요청에서 티켓 재고 정합성을 어떻게 지키는가"를 **6가지 락 전략 + 1개 대기열 모듈**로 구현하고, 같은 API 형태(`POST /api/v{n}/concerts/{id}/reserve`)로 노출해 부하 테스트로 비교할 수 있게 만든 포트폴리오다.

```
요청 흐름 (예약 1건)
─────────────────────────────────────────────────────────────
V1  No Lock          : 컨트롤러 → 서비스(@Transactional) → 조회·검증·DECR·INSERT  (동기화 없음)
V2  Pessimistic      : SELECT ... FOR UPDATE 로 행 잠금 → 차감 → INSERT
V3  Optimistic       : @Version 충돌 시 지수백오프 재시도 (서비스가 트랜잭션 외부에서 재시도 루프)
V4  Lettuce Spin     : Redis SETNX 스핀락 → (커밋 후) Lua compare-and-delete 해제
V5  Redisson Pub-Sub : RLock.tryLock(watchdog 자동연장) → 커밋 → unlock
V6  Redis 선점+Kafka : Redis DECR로 즉시 선점 → Outbox 저장 → 커밋 후 Kafka 발행 → 컨슈머가 비동기 INSERT
WaitingRoom         : ZSet 대기열 토큰 검증 → Redis DECR 선점 → 즉시 응답 → @Async DB INSERT
```

핵심 분기점은 **"DB 행(Concert.stock)을 SSOT로 쓰느냐(V1~V5), Redis 카운터를 SSOT로 쓰느냐(V6/WaitingRoom)"** 이다.

### 패키지 맵

```
com.example.ticketing
├── TicketingApplication        @EnableJpaAuditing/@EnableAsync/@EnableScheduling
├── concert/                    공통 도메인: Concert 엔티티 + CRUD + 재고초기화 API
├── reservation/
│   ├── controller/             ReserveControllerV1~V6 (URL 라우팅만)
│   ├── domain/                 Reservation, ReservationStatus
│   ├── dto/                    ReserveRequest(userId), ReserveResponse(reservationId,status,ticketToken)
│   ├── service/                TicketService(인터페이스) + v1~v6/ 구현
│   │   ├── v1 TicketServiceV1
│   │   ├── v2 TicketServiceV2
│   │   ├── v3 TicketServiceV3 + OptimisticLockRetryer + TicketTransactionV3
│   │   ├── v4 TicketServiceV4 + TicketTransactionV4
│   │   ├── v5 TicketServiceV5 + TicketTransactionV5
│   │   └── v6 TicketServiceV6 (트랜잭션 클래스 없음 — 자기 자신이 트랜잭션)
│   ├── kafka/                  TicketConsumer, ReservationMessage
│   └── outbox/                 OutboxEvent, OutboxStatus, OutboxCreatedEvent, OutboxRelay, Repository
├── waitingroom/                구 V7 — 별도 모듈 (URL /api/waitingroom)
│   ├── controller/             ReservationController, QueueController
│   ├── service/                ReservationService, ReservationTransaction, QueueCommandService, QueueQueryService
│   ├── repository/             QueueRedisRepository (Lua ADMIT/PROMOTE)
│   ├── scheduler/              QueueScheduler (3초 주기)
│   ├── dto/                    ReserveRequest, QueueTokenResponse, QueueStatusResponse
│   └── WaitingRoomConst        정원 200, TTL 등 상수
├── payment/                    Payment 엔티티 + 결제 시뮬레이션(100~200ms 슬립)
├── dashboard/                  TestResult 저장 + Thymeleaf/Chart.js 대시보드
└── global/
    ├── config/                 AsyncConfig, KafkaConfig, RedissonConfig
    ├── exception/              커스텀 예외 5종 + GlobalExceptionHandler
    ├── lock/                   LettuceLockRepository (V4)
    └── stock/                  RedisStockRepository (V6/WaitingRoom 공용 Redis 재고)
```

---

## 1. 기술 스택 / 빌드 (`build.gradle`)

- **Spring Boot 4.0.6**, Java 17 toolchain, Gradle.
- 의존성: web, data-jpa, **data-redis**, validation, thymeleaf, actuator, spring-aop/aspects.
- DB: `mysql-connector-j` (runtimeOnly).
- Redis 락: **Redisson 3.50.0** 직접 의존(부트 스타터 아님). 일반 Redis 작업은 `spring-boot-starter-data-redis`의 `StringRedisTemplate` 사용.
- Kafka: `spring-kafka` (+ test).
- Lombok: `@Getter`, `@RequiredArgsConstructor`, `@Slf4j` 등.
- **주목할 점**: Spring Boot 4 / Jackson 3 이행으로 코드에서 Jackson import가 `tools.jackson.databind.ObjectMapper`, `tools.jackson.core.JacksonException` (구 `com.fasterxml.jackson`이 아님). `TicketServiceV6`, `TicketConsumer`에서 확인.
- 테스트: JUnit Platform, AssertJ, `spring-kafka-test`.

### 부트스트랩 (`TicketingApplication`)
3개의 전역 활성화 애너테이션이 전체 기능을 켠다:
- `@EnableJpaAuditing` → `@CreatedDate`(Reservation, OutboxEvent, Payment, TestResult) 자동 채움.
- `@EnableAsync` → `OutboxRelay.onOutboxCreated`, `ReservationTransaction.saveReservationAsync` 의 `@Async` 동작.
- `@EnableScheduling` → `OutboxRelay.recoverStuckEvents`(5분), `QueueScheduler.processQueue`(3초).

---

## 2. 공통 도메인

### 2.1 `Concert` (concert/domain/Concert.java)
```java
id, title, totalStock, stock, @Version Long version
create(title, stock)  // totalStock=stock=stock
decrease()            // isOutOfStock()면 IllegalStateException("재고 없음"), 아니면 stock--
isOutOfStock()        // stock <= 0
resetStock(stock)     // stock=totalStock=stock
```
- **`@Version`이 모든 버전에 존재한다.** 이게 함정 #1: V1조차 Concert를 `save()`(=update)할 때 낙관적 락이 걸려, 동시 update 충돌 시 `ObjectOptimisticLockingFailureException`이 날 수 있다. 즉 V1은 "완전 무방비"가 아니라 JPA dirty checking + @Version이라는 다층 방어가 일부 작동한다. → V1 오버부킹 수치가 이론보다 낮게 나올 수 있음(문서화된 의도된 한계).
- `decrease()`의 가드(`IllegalStateException`)는 도메인 최후 방어선. 단 메시지가 한글 문자열이고 커스텀 예외가 아니라 `GlobalExceptionHandler`의 `handleGeneral`(500)으로 잡힌다.

### 2.2 `Reservation` (reservation/domain)
```java
id, concertId, userId, ticketToken(String), @Enumerated status, @CreatedDate createdAt
of(concertId,userId,status)                 // V1~V5, WaitingRoom 용 (ticketToken=null)
ofV6(concertId,userId,status,ticketToken)   // V6 전용 (ticketToken 채움)
updateStatus(status)
```
- `ReservationStatus = {SUCCESS, FAIL, PENDING}`. 실제로 FAIL은 어디서도 저장하지 않는다(실패는 예외로 처리). PENDING은 V6 즉시 응답에만 사용.
- **외래키·유니크 제약이 없다.** `ticketToken`에 DB unique 인덱스가 없어, V6 멱등성은 순전히 애플리케이션 레벨 `existsByTicketToken` 체크에 의존한다(컨슈머 concurrency=1 단일 파티션이라 레이스가 없다는 전제).

### 2.3 DTO
- `ReserveRequest(@NotNull Long userId)` — 예약 입력.
- `ReserveResponse(Long reservationId, ReservationStatus status, String ticketToken)` — 2-arg 보조 생성자(ticketToken=null)가 V1~V5에서 쓰이고, 3-arg가 V6에서 쓰인다.

---

## 3. 공통 API

### 3.1 `ConcertController` (`/api/concerts`)
| 메서드 | 경로 | 용도 |
|---|---|---|
| POST | `/api/concerts` | 공연 생성(데모/테스트) |
| GET | `/api/concerts?page&size` | 목록(시나리오 B step1) |
| GET | `/api/concerts/{id}` | 상세/잔여석(시나리오 B step2) |
| GET | `/api/concerts/{id}/stock` | 현재 DB 재고 |
| POST | `/api/concerts/{id}/reset?stock=` | **재고 초기화(테스트 필수)** — `@Positive` 검증, 기본 100 |

### 3.2 `ConcertService` — Redis-DB 재고 동기화의 핵심
- `createConcert`, `resetStock`는 DB를 갱신한 뒤 **`TransactionSynchronizationManager.registerSynchronization(afterCommit)`** 으로 커밋 직후 `redisStockRepository.initStock()`을 호출한다.
- **왜 afterCommit?** DB 커밋이 성공해야 Redis를 맞춘다. 트랜잭션 도중 Redis를 먼저 쓰면 롤백 시 DB(0)와 Redis(불일치)가 어긋난다. 이 패턴은 V6/WaitingRoom의 Redis SSOT 전제(둘이 항상 같은 시작값)를 성립시키는 장치다.
- **함의**: V6/WaitingRoom 테스트나 부하 전에는 반드시 `reset` 또는 `createConcert`를 거쳐 Redis 재고가 세팅돼 있어야 한다. 안 그러면 `RedisStockRepository.decrement`가 null 키에 DECR → Redis는 -1부터 시작(즉시 SoldOut).

> ⚠️ **주의**: `reset`/`createConcert`는 `@Profile` 제한이 없다. CLAUDE.md는 "reset은 `@Profile("!prod")`로 비활성화"라고 적었지만 **현재 코드엔 적용돼 있지 않다**(컨트롤러·서비스 어디에도 `@Profile` 없음). 문서-코드 불일치.

---

## 4. 버전별 상세 분석

모든 V1~V5 서비스의 비즈니스 로직 4단계는 동일하다: `findConcert → validateStockAvailable → decreaseStock(concert.decrease()) → saveReservation(SUCCESS)`. **차이는 "어떻게 동시 진입을 막느냐"뿐**이다. 컨트롤러(ReserveControllerV1~V6)는 전부 URL prefix만 다른 동일 형태(`@PostMapping("/concerts/{concertId}/reserve")`)다.

### 4.1 V1 — No Lock (기준선)
`TicketServiceV1` — `@Transactional` 한 개. 조회→검증→차감→저장을 동기화 없이 수행.
- **Race Condition 발생 지점**: 여러 스레드가 같은 `stock` 값을 읽고 각자 `decrease()` → 마지막 update만 반영되는 Lost Update. 동시 500명/재고 100 → 오버부킹.
- 단, 4.2.1에서 말한 `@Version` 때문에 일부 update가 충돌 예외로 걸러질 수 있어 완전한 무방비는 아니다.

### 4.2 V2 — Pessimistic Lock
`TicketServiceV2` — `@Transactional(timeout = 5)`.
- `ConcertRepository.findByIdWithPessimisticLock` = `@Lock(PESSIMISTIC_WRITE)` + `@Query` → `SELECT ... FOR UPDATE`.
- 첫 스레드가 행 쓰기락을 잡으면 나머지는 트랜잭션 끝까지 DB에서 대기 → 직렬화. 정합성 보장.
- `timeout=5`: 락 대기가 5초 넘으면 트랜잭션 타임아웃(데드락/롱홀딩 방어).
- **트레이드오프**: DB 커넥션을 잡은 채 대기 → 경합이 심하면 커넥션 풀 고갈·TPS 저하.
- **데드락 시연**: `DeadlockTest`가 두 스레드가 concert A→B / B→A 순으로 `FOR UPDATE`를 교차로 잡게 만들어 MySQL 데드락 감지 또는 `@Transactional(timeout=3)` 타임아웃으로 최소 하나가 예외를 받는 것을 검증한다.

### 4.3 V3 — Optimistic Lock + 재시도
3개 클래스로 분리된 것이 핵심:
- **`TicketServiceV3`** (트랜잭션 없음): `retryer.executeWithRetry(() -> transaction.reserveInTransaction(...), concertId, MAX_RETRY=10)`.
- **`TicketTransactionV3`** (`@Transactional`): 실제 조회·차감·저장. 커밋 시점에 `@Version` 불일치면 `ObjectOptimisticLockingFailureException` 발생.
- **`OptimisticLockRetryer`**: 예외를 잡아 재시도. 재시도 한도 초과 시 `ReservationFailedException`(409).

**왜 트랜잭션을 분리했나?** 재시도는 반드시 **새 트랜잭션**에서 해야 한다. 같은 트랜잭션 안에서 재시도하면 이미 rollback-only 마킹돼 무의미하다. 그래서 `executeWithRetry`(트랜잭션 밖)가 매 시도마다 `reserveInTransaction`(트랜잭션 안)을 새로 호출 → 각 시도가 독립 트랜잭션. 이게 **self-invocation(자기호출) 프록시 우회**의 정석 패턴이며 V3/V4/V5가 모두 "Service(락/재시도) + Transaction(@Transactional Bean) 분리" 구조를 쓰는 이유다.

**백오프(함정 #2 수정)**:
```java
backoffMs   = 50ms * 2^retryCount      // 지수 증가
cappedMs    = min(backoffMs, 1000ms)   // 상한
jitteredMs  = random() * cappedMs      // 지터(0~capped 사이 랜덤)
Thread.sleep(jitteredMs)
```
고정 50ms sleep을 쓰면 충돌한 스레드들이 정확히 50ms 후 다시 동시 충돌(Thundering Herd)한다. 지수+지터로 재충돌 시점을 분산시킨다.

### 4.4 V4 — Lettuce Spin Lock
- **`TicketServiceV4`**: `acquireSpinLock` → `transaction.reserveInTransaction` → `finally`에서 `releaseLock`.
  - 스핀: `tryLock`이 null이면 `Thread.sleep(100ms)` 후 재시도, `MAX_SPIN_COUNT=100`회(=약 10초) 초과 시 `LockAcquisitionFailedException`.
- **`LettuceLockRepository`** (global/lock):
  - `tryLock`: `SET lock:concert:{id} {uuid} NX EX 3s` (`setIfAbsent` + TTL). 성공 시 uuid(lockValue) 반환, 실패 시 null.
  - `releaseLock`: **Lua compare-and-delete** — `if get(key)==ARGV[1] then del(key) else 0`. (함정 #3 수정)
- **`TicketTransactionV4`** (`@Transactional`): 비즈니스 로직.

**핵심 트레이드오프(코드 주석에 상세 기록됨)**:
- Lua CAS 해제로 **"남의 락을 지우는"** 문제(A의 TTL 만료 → A의 finally가 B의 락 삭제)는 막았다.
- 그러나 **"실행 도중 TTL(3초) 만료"** 자체는 못 막는다. 워치독이 없어서, 트랜잭션이 3초를 넘기면 락이 풀려 다른 스레드 진입 → 오버부킹 가능. 이게 V4의 본질적 한계이며 V5(Redisson 워치독)로 해결.
- **트랜잭션 경계 주의**: `@Transactional`은 `TicketTransactionV4`에만 있고 `TicketServiceV4`(락 획득/해제)에는 없다. 그래서 순서가 `락 획득 → [트랜잭션 시작 → 커밋] → 락 해제`가 되어, **커밋 후 락 해제**가 보장된다. 만약 락 해제가 커밋 전에 일어나면 다른 스레드가 아직 커밋 안 된 재고를 보고 진입한다.

### 4.5 V5 — Redisson Pub-Sub Lock
- **`TicketServiceV5`**: `redissonClient.getLock("lock:concert:"+id)` → `lock.tryLock(5, SECONDS)` → `transaction.reserveInTransaction` → `finally`에서 `isHeldByCurrentThread()` 확인 후 `unlock()`.
- **`RedissonConfig`**: `useSingleServer().setAddress("redis://host:port")`, `destroyMethod="shutdown"`.
- **`TicketTransactionV5`** (`@Transactional`): 비즈니스 로직.

**V4 대비 개선점**:
1. **Pub-Sub 대기**: 스핀 폴링(100ms sleep 반복) 대신, 락 해제 시 Redis Pub-Sub 메시지로 대기 스레드를 깨운다 → CPU·Redis 부하 감소, 지연 단축.
2. **워치독(함정 #4 수정)**: `tryLock(waitTime, unit)` **2인자** 버전을 쓴다. leaseTime을 명시하지 않으면 Redisson 워치독이 기본 30초 lease를 10초마다 자동 갱신 → 작업이 끝날 때까지 락 유지. `tryLock(5, 3, SEC)`처럼 leaseTime을 주면 워치독이 꺼져 V4와 같은 "실행 중 만료" 문제가 재발한다.
3. `isHeldByCurrentThread()` 가드로 내 락만 해제(InterruptedException 시 재진입 안전).

### 4.6 V6 — Redis 선점 + Outbox + Kafka 비동기
가장 복잡. 동기 응답을 포기하고(즉시 PENDING) 처리량을 극대화.

**`TicketServiceV6.reserve` (`@Transactional`)** — V6는 트랜잭션 분리 클래스가 없고 서비스 자신이 트랜잭션:
```
1. remaining = redisStockRepository.decrement(concertId)   // Redis DECR, 원자적 선점
2. if remaining < 0:
       redisStockRepository.increment(concertId)           // 음수 보정 복구
       throw SoldOutException                               // → 트랜잭션 롤백 (Outbox 미저장)
3. ticketToken = UUID
4. payload = JSON(ReservationMessage(concertId,userId,ticketToken))
5. outboxEventRepository.save(OutboxEvent.create(...))      // PENDING 상태로 DB 저장
6. eventPublisher.publishEvent(new OutboxCreatedEvent(id))  // 스프링 이벤트 발행
7. return ReserveResponse(null, PENDING, ticketToken)       // 즉시 응답
```
- **선점이 Redis DECR로 끝난다.** DB 재고(Concert.stock)는 V6에서 건드리지 않는다 → 재고 SSOT = Redis(함정 #8 수정). DECR은 단일 원자 연산이라 오버부킹이 구조적으로 불가능(N장이면 N+1번째 호출이 반드시 -1).
- **음수 보정**: 동시에 여러 스레드가 DECR하면 일부가 -1, -2…를 받는다. 받은 쪽은 즉시 INCR로 되돌려 Redis가 0 밑으로 내려가 머무르지 않게 한다. (`successCount + redisStock == initialStock` 불변식이 테스트로 검증됨.)
- **왜 Outbox?** "Redis 선점(성공)"과 "Kafka 발행"을 한 트랜잭션으로 묶을 수 없다(Redis·Kafka는 DB 트랜잭션 밖). 직접 발행하면 "커밋은 됐는데 발행 실패" 또는 "발행은 됐는데 롤백" 같은 이중쓰기 문제가 생긴다. → **OutboxEvent를 같은 DB 트랜잭션에 INSERT**하고, 발행은 커밋 후로 미룬다.

**`OutboxRelay` — 발행 책임 (TicketProducer는 존재하지 않음)**:
- **정상 경로** `onOutboxCreated`: `@Async` + `@TransactionalEventListener(AFTER_COMMIT)`. V6 트랜잭션 커밋 직후 비동기로 해당 OutboxEvent를 조회해 `publishAndSave`.
- **복구 경로** `recoverStuckEvents`: `@Scheduled(fixedDelay=300_000ms=5분)`. `PENDING` 상태로 5분 이상 묵은 이벤트(크래시/Kafka 장애로 발행 못 한 것)를 재발행 → **at-least-once 보장**.
- `publishAndSave`: `kafkaTemplate.send(topic, key=concertId, payload).get(5초)` 동기 대기. 성공 시 `markPublished()`(status=PUBLISHED, publishedAt), 실패 시 `incrementRetry()`(retryCount++, 5회 도달 시 FAILED 격리).
- `alertDeadLetteredEvents`: FAILED 건수>0이면 `log.error`로 수동 조치 경고(복구 대상에서 빠지므로 방치 방지).

**`TicketConsumer` — 멱등 소비 + DLT (`@Transactional`)**:
```
@KafkaListener(topics="${ticketing.kafka.topic}", concurrency="1")  // 단일 컨슈머 = 순차 처리
1. message = deserialize(payload)
2. if existsByTicketToken(token): ack.acknowledge(); return       // 멱등 — 중복 스킵
3. saveReservation(...)  // Reservation.ofV6(SUCCESS, ticketToken) INSERT — 재고는 안 건드림
4. registerSynchronization(afterCommit -> ack.acknowledge())     // 커밋 후 오프셋 커밋
```
- **함정 #5 수정**: `ack.acknowledge()`를 `afterCommit` 콜백으로 미룬다. 커밋 전에 ack하면 DB 저장 실패 시 오프셋만 넘어가 메시지 영구 유실. afterCommit으로 옮기면 커밋 성공해야 오프셋 전진.
- **함정 #7 수정 (DLT)**: afterCommit ack 수정 후엔 커밋 실패 시 ack가 안 불려 단일 파티션 오프셋이 막혀 무한 재시도(HoL 블로킹) 위험. `KafkaConfig`가 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`로 **2회 재시도(FixedBackOff 1초 간격) 후 `<topic>.DLT`로 격리**.
- **함정 #8 수정**: 컨슈머가 예전엔 `concert.decrease()`로 DB 재고를 재차감했으나, 그러면 Redis·DB 두 재고 카운터가 분기. 지금은 **예약 INSERT만** 한다(재고 SSOT=Redis). 주석에 명시.

**`getReservationStatus(ticketToken)` — 폴링 조회 (신규)**:
```
1. findByTicketToken → 있으면 SUCCESS (컨슈머 처리 완료)
2. outboxEventRepository.existsByTicketToken → 있으면 PENDING (처리 중)
3. 둘 다 없으면 ReservationNotFoundException(404)
```
- `ReserveControllerV6`에 `GET /api/v6/reservations/{ticketToken}/status` 엔드포인트로 노출.
- ⚠️ **문서-코드 불일치**: CLAUDE.md/함정 #6은 "폴링 조회 API 미구현"이라 적었지만, **현재 코드엔 구현돼 있다**(최근 커밋 `feat: V6 예약 상태 폴링 API 추가`, `cb1a3ca`). README/CLAUDE.md가 stale.

**V6 트랜잭션 분리 모델**:
| 단계 | 트랜잭션 | 스레드 |
|---|---|---|
| Redis DECR + Outbox INSERT | V6 서비스 TX | 요청 스레드 |
| Kafka 발행 | (TX 밖, AFTER_COMMIT) | `@Async` 풀(기본 SimpleAsyncTaskExecutor) |
| 예약 INSERT | 컨슈머 TX | Kafka 리스너 스레드(concurrency=1) |

---

## 5. WaitingRoom 모듈 (구 V7, `/api/waitingroom`)

목적이 다르다: V1~V6은 "단건 직렬화", WaitingRoom은 **"유입 트래픽 자체를 통제(서버 보호)"**. 그래서 버전 라인에서 빠져 독립 패키지·URL·클래스명(V7 접미사 없음)으로 분리됐다.

### 5.1 상수 (`WaitingRoomConst`)
```
PROCESSING_QUEUE_SIZE   = 200          // 동시 처리(예약 진입) 정원
PROCESSING_TOKEN_TTL_MS = 30분
WAITING_TOKEN_TTL_MS    = 60분
PROCESSING_KEY_PREFIX   = "queue:processing:"
WAITING_KEY_PREFIX      = "queue:waiting:"
STATUS = PROCESSING | WAITING | NOT_IN_QUEUE
```

### 5.2 큐 자료구조 — Redis Sorted Set + Lua 원자화 (`QueueRedisRepository`)
두 개의 ZSet(`queue:processing:{id}`, `queue:waiting:{id}`)을 쓴다. **score = 만료시각(현재 ms + TTL)** 으로, FIFO 순서(대기열 rank)와 만료 제거(score 범위 삭제)를 동시에 해결한다.

**핵심: 두 개의 Lua 스크립트로 TOCTOU(Time-of-check/Time-of-use) 제거 (함정/하드닝 8-5)**:
- **`ADMIT_SCRIPT`** (`admitOrEnqueue`): "입장 판정 + 등록"을 원자 1회로.
  ```lua
  if ZCARD(처리열) < 정원 and ZCARD(대기열) == 0 then
      ZADD 처리열 <처리만료score> <토큰>; return 1   -- 즉시 입장
  else
      ZADD 대기열 <대기만료score> <토큰>; return 0   -- 대기 진입
  end
  ```
  자리 있고 + 대기열이 비었을 때만 즉시 입장(대기 줄이 있으면 새치기 금지). 판정과 등록 사이에 다른 요청이 끼어들 수 없어 정원 초과 입장 불가.
- **`PROMOTE_SCRIPT`** (`promoteWaiting`): 처리열 빈자리만큼 대기열 선두(ZRANGE 0..slots-1)를 ZREM→ZADD로 원자 승격, 이동 수 반환.

**기타 연산**:
- `isInProcessingQueue` (score!=null), `getWaitingPosition` (rank+1), `removeFrom{Processing,Waiting}`, `getProcessingCount`/`getWaitingCount`(ZCARD), `clearQueues`(테스트), `findAllWaitingKeys`.
- `purgeExpiredTokens`: 두 prefix를 SCAN으로 순회하며 `removeRangeByScore(-inf, now)` → 만료 토큰 제거.
- **함정/하드닝 8-5: `KEYS` → `SCAN`**: `scanKeys`가 `KEYS pattern`(단일스레드 Redis 풀스캔 블로킹) 대신 커서 기반 `SCAN`(count=100)을 쓴다. 프로덕션 Redis 블로킹 방지.

### 5.3 토큰 발급 / 상태 조회
- **`QueueCommandService.issueTokenAndEnqueue`**: UUID 토큰 생성 → `admitOrEnqueue` → 결과에 따라 `QueueTokenResponse.processing/waiting`. `promoteWaitingToProcessing`(스케줄러용): 모든 대기열 키를 찾아 concertId 추출 후 `promoteWaiting`.
- **`QueueQueryService.getQueueStatus`**: 처리열이면 `processing()`, 대기열이면 위치+예상대기(`position / 200 * 5분`) 반환, 아니면 `notInQueue()`.
- **`QueueController`**: `POST /token?userId`, `GET /status?token`.

### 5.4 예약 처리 (`ReservationService`)
```
reserve(concertId, userId, queueToken):
  1. validateInProcessingQueue   // 처리열에 토큰 없으면 LockAcquisitionFailedException (= 입장권 검증)
  2. preemptStock:
       remaining = redisStock.decrement
       queueCommandService.removeFromProcessing(token)   // 토큰 1회용 소비
       if remaining < 0: redisStock.increment; throw SoldOutException
  3. transaction.saveReservationAsync(concertId, userId)  // @Async, 결과 안 기다림
  4. return ReserveResponse(null, SUCCESS)               // 즉시 SUCCESS
```
- V6와 차이: V6는 **PENDING + Kafka**, WaitingRoom은 **즉시 SUCCESS + @Async 직접 DB**. 메시지 큐·Outbox 없음(실시간 응답 가능).
- **토큰 소비 순서 주의**: `removeFromProcessing`이 DECR **직후·SoldOut 판정 전**에 호출된다. 즉 매진으로 실패해도 토큰은 이미 처리열에서 빠진다(1회용). 재시도하려면 토큰 재발급 필요.

### 5.5 비동기 DB 저장 (`ReservationTransaction`)
```java
@Async("waitingRoomTaskExecutor") @Transactional
saveReservationAsync(concertId, userId):
  try: reservationRepository.save(Reservation.of(SUCCESS))
  catch: redisStockRepository.increment(concertId)   // DB 실패 시 Redis 재고 복구(보상)
```
- **전용 스레드풀 `waitingRoomTaskExecutor`** (`AsyncConfig`, 최근 커밋 `84c8c22`): core=50, max=200, queue=200, keepAlive=60s, `CallerRunsPolicy`(풀·큐 포화 시 호출 스레드가 직접 실행 → 백프레셔). 기본 `@Async` 풀과 분리해 대기열 부하가 다른 비동기 작업(OutboxRelay 등)을 굶기지 않게 한다.
- **결과적 일관성(Eventually Consistent)**: 응답은 SUCCESS지만 DB는 나중에. 저장 실패 시 Redis 재고만 복구할 뿐, 이미 나간 SUCCESS 응답은 못 되돌린다(보상의 한계 — 사용자에겐 성공으로 보이나 예약 레코드는 없을 수 있음).

### 5.6 스케줄러 (`QueueScheduler`)
`@Scheduled(fixedRate=3000)` 3초마다: `purgeExpiredTokens()`(만료 토큰 제거) → `promoteWaitingToProcessing()`(빈자리만큼 대기→처리 승격). 이로써 처리 끝났거나(removeFromProcessing) 만료된 자리에 대기자가 자동 진입.

---

## 6. 글로벌 인프라 (`global/`)

### 6.1 `RedisStockRepository` (global/stock) — V6/WaitingRoom 공용
`stock:concert:{id}` 키에 대해 `initStock`(set), `decrement`(DECR), `increment`(INCR), `getStock`(get, null→0). DECR/INCR이 원자 연산이라 분산락 없이도 재고 선점이 안전하다.

### 6.2 예외 + `GlobalExceptionHandler`
| 예외 | HTTP | 발생처 |
|---|---|---|
| `ConcertNotFoundException` | 404 | 공연 미존재 (PaymentService는 예약 미존재에도 이걸 던짐 — 약간 부정확) |
| `ReservationNotFoundException` | 404 | V6 getStatus 폴링 |
| `SoldOutException` | 409 | 모든 매진 |
| `LockAcquisitionFailedException` | 409 | V4 스핀 초과, V5 락 실패, WaitingRoom 미등록 토큰 |
| `ReservationFailedException` | 409 | V3 재시도 한도 초과 |
| `ConstraintViolation`/`MethodArgumentNotValid` | 400 | 검증 실패 |
| `Exception` | 500 | 그 외(예: `Concert.decrease`의 IllegalStateException) |

모두 `{"error": "..."}` JSON. `@RestControllerAdvice` 단일 처리.

### 6.3 `KafkaConfig`
- Producer: `acks=all`, `enable.idempotence=true`, `retries=3`, String 직렬화. (Outbox에서 멱등 발행)
- Consumer: `group-id` from yml, `auto-offset-reset=earliest`, **`enable.auto.commit=false`** (수동 ack 전제).
- Listener factory: `AckMode.MANUAL_IMMEDIATE` + `DefaultErrorHandler(DeadLetterPublishingRecoverer, FixedBackOff(1초, 2회))`.

### 6.4 `AsyncConfig`
위 5.5 참조. `waitingRoomTaskExecutor` 빈만 정의(다른 `@Async`는 스프링 기본 executor 사용).

---

## 7. Payment (시나리오 B 4단계 플로우용)

- `Payment(id, reservationId, @Enumerated status, @CreatedDate)`, status={PAID, FAILED}.
- `PaymentService.pay(reservationId)`: 예약 조회 → **`simulateExternalPgDelay`(100~200ms 랜덤 sleep, 외부 PG 흉내)** → 예약 status=SUCCESS 갱신 → Payment(PAID) 저장.
- `POST /api/payments {reservationId}`.
- 동시성 제어 대상이 아니라 "실제 플로우의 마지막 단계 + 인위적 레이턴시" 역할.

---

## 8. 대시보드 (`dashboard/`)

- **`TestResult`** 엔티티: version(enum V1~V6), lockType(enum), scenarioType(A/B), concurrentUsers, initialStock, totalRequests, successCount, **overBookingCount**, tps, p99ResponseMs, errorRate, memo, @CreatedDate. 부하 결과를 수동 저장하는 표.
  - enum: `LockVersion{V1~V6}`, `LockType{NO_LOCK, PESSIMISTIC, OPTIMISTIC, LETTUCE_SPIN, REDISSON_PUBSUB, KAFKA_QUEUE}`, `ScenarioType{SCENARIO_A, SCENARIO_B}`. **WaitingRoom은 enum에 없다** → 대시보드 비교 대상에서 빠짐(Phase 8-8 미완과 일치).
- **`TestResultController`** (`/api/test-results`): POST(저장, 201), GET(전체 or scenario 필터), DELETE(204).
- **`DashboardController`** (`/dashboard`, Thymeleaf): 필터(scenario/version/users) → `findByFilters` 테이블 + `buildChartData`(scenario·users 기준, 기본 A/500)로 버전별 TPS·P99·오버부킹·에러율 4개 막대 차트.
- **`TestResultRepository`**: 동적 필터 JPQL(`:x IS NULL OR ...`), `findLatestPerVersionAndScenario`(version·scenario·users별 최신 1건 = `MAX(id)` 그룹).
- **`DashboardService`**: 위를 위임 + `save`/`delete`(`@Transactional`).
- **프론트** (`dashboard.js` + `templates/dashboard/index.html`): Chart.js CDN, 다크 테마, `chartData`(서버 주입 JS 객체)로 4개 `makeBarChart`. `deleteResult(id)`는 fetch DELETE 후 reload.

---

## 9. 설정 / 프로파일

- **`application.yml`**: `profiles.default=local`, MySQL 드라이버, Kafka consumer/producer 직렬화 기본값, `ticketing.kafka.topic=ticket-reservation`.
- **`application-local.yml.example`**: 로컬 템플릿(실제 `application-local.yml`은 git ignore, 민감정보 분리 — 커밋 `26aa5c5`). `ddl-auto=update`, `show-sql=true`, MySQL `localhost:3306/ticketing`, Redis `localhost:6379`, Kafka `localhost:9092`.
- **`application-prod.yml`**: 전부 환경변수(`${DB_URL}`, `${REDIS_HOST}` 등), `ddl-auto=validate`, `show-sql=false`.
- **`kafka-docker-compose.yml`**: Confluent zookeeper 2181 + kafka 9092(단일 브로커, auto-create-topics on, replication=1). 로컬 개발용.

---

## 10. 테스트 (`src/test`)

각 버전 통합 테스트 + 데드락 + 멱등성. 공통 패턴: `@SpringBootTest`, `ExecutorService` 500스레드 + `CountDownLatch`로 동시성 재현, `AtomicInteger`로 성공/실패 집계.

| 테스트 | 검증 핵심 | 외부 의존 |
|---|---|---|
| `ConcurrencyTestV1` | 단건 정상 / 재고0 SoldOut / **오버부킹 발생(Race 증명, 비결정적)** | MySQL, Redis |
| V2~V5 (동형) | 동시 500/재고100 → **오버부킹 0** | MySQL, Redis |
| `ConcurrencyTestV6` | 즉시 PENDING / Redis 음수 미발생 / 오버부킹 0 / Outbox PENDING→PUBLISHED / 컨슈머 DB 저장 / **Redis재고==DB재고** / `successCount+redisStock==initialStock` | MySQL, Redis, **Kafka** |
| `ConcurrencyTestWaitingRoom` | 처리열 즉시입장 / 미등록토큰 차단 / 재고0 복구 / 500명 중 200 입장·오버부킹 0 / **대기→승격→예약 전체 플로우** | MySQL, Redis |
| `DeadlockTest` | A→B / B→A 교차 `FOR UPDATE`로 데드락 → 감지/타임아웃으로 해소(최소 1 예외) | MySQL |
| `OutboxIdempotencyTest` | 동일 ticketToken 2회 컨슈 → **예약 1건만**(멱등). 컨슈머를 no-op ack로 직접 호출(Kafka 불필요) | MySQL, Redis |

비동기 검증은 폴링 헬퍼(`awaitReservationCount`, `awaitOutboxPublished`)로 최대 15~30초 대기.
주의: V1 오버부킹 테스트는 `@Version` 영향으로 "이번 실행엔 오버부킹 없음"이 나올 수 있어 단언이 아니라 출력으로 처리(비결정성 인정).

---

## 11. 부하 테스트 (`load-test/gatling`, Maven/Scala)

- **`Feeders.userFeeder`**: 매 요청 랜덤 userId(1~100만) → 동일 사용자 중복 예약 방지.
- **`ScenarioASimulation`** (극한 경합/정합성): `-DVERSION`, `-DUSERS` 시스템 프로퍼티. `atOnceUsers(users)`로 일시 폭주. 단일 `POST /api/{version}/concerts/1/reserve`, `status.in(200,409,500)` 전부 허용(오버부킹 측정 목적), 어설션 `successfulRequests >= 0`(사실상 무조건 통과). 실행 전 `reset?stock=100` 필수.
- **`ScenarioBSimulation`** (처리량/실플로우): `rampUsers(users).during(10s)`로 점진 투입. 4단계(목록→상세→예약→결제) + think time(pause 1~2s, 2~3s). 결제는 `reservationId` 있을 때만(`doIf`). 어설션 `successfulRequests >= 90%`. 실행 전 `reset?stock=100000` 필수.
- `target/gatling/*`에 다수의 과거 리포트(HTML)가 빌드 산출물로 남아있다(소스 아님).

---

## 12. 횡단 관심사 — 설계 패턴 요약

1. **Service/Transaction 분리 (self-invocation 회피)**: V3·V4·V5는 락/재시도를 트랜잭션 밖(`TicketServiceVn`)에서, 비즈니스 로직을 트랜잭션 안(`TicketTransactionVn` 별도 빈)에서 수행. 같은 클래스 내부 호출은 프록시를 안 타 `@Transactional`이 무시되므로 **반드시 다른 빈**으로 분리. V6은 메서드 1개라 서비스 자체가 트랜잭션.
2. **트랜잭션 경계와 락/ack 순서**: V4/V5는 "락 해제 < 커밋 이후", V6 컨슈머는 "ack < 커밋 이후"(afterCommit), ConcertService는 "Redis init < 커밋 이후"(afterCommit). 일관되게 **외부 부수효과를 커밋 뒤로** 미루는 원칙.
3. **재고 SSOT 이원화**: V1~V5 = DB행(`Concert.stock`, 락으로 보호), V6/WaitingRoom = Redis 카운터(DECR 원자성). 후자는 DB와의 동기화를 `ConcertService`의 afterCommit `initStock` + 실패 시 보상 INCR로 맞춘다.
4. **at-least-once + 멱등 = 사실상 exactly-once**: Outbox(복구 재발행) + Producer 멱등 + Kafka + Consumer `existsByTicketToken` 멱등 + DLT. 중복 전달돼도 예약 1건.
5. **상수화·로깅 규약**: 매직넘버 상수화, 모든 락 이벤트 `[Vn] ...` INFO 로깅(부하 분석용).

---

## 13. 문서-코드 불일치 / 주의점 (실측 → 조치 반영 2026-06-22)

최초 분석 시 발견한 항목 + 이후 조치 결과. **#1·#2·#4·#5는 수정 완료**, #3은 무시(파일 이동 중), #5·#6은 통합됨.

1. ✅ **[수정완료] V6 폴링 API**. 최초 문서엔 "미구현"이었으나 실제 `ReserveControllerV6.getStatus` + `TicketServiceV6.getReservationStatus`가 존재(커밋 cb1a3ca). → **CLAUDE.md**(V6 전략 설명·함정 표 #6·Phase 7)를 "구현됨(`GET /api/v6/reservations/{ticketToken}/status`)"으로 갱신.
2. ✅ **[수정완료] `/reset` 프로덕션 비활성화**. 기존엔 `@Profile` 가드가 없어 prod에서도 재고 초기화 가능했음. `@Profile`은 핸들러 메서드 단위로 동작하지 않으므로, 조회 API(`ConcertController`)와 분리한 신규 **`ConcertTestController`(`@Profile("!prod")`)**로 `reset` 이전 → prod에서는 빈 미등록(404). contextLoads로 매핑 충돌 없음 확인.
3. ⏭️ **[무시] `docs/performance-report.md`·`tradeoff-summary.md`·level-1~13 부재**. 문서 파일 재배치 작업 중이라 코드 변경 대상 아님.
4. ✅ **[수정완료] WaitingRoom 대시보드 enum**. `LockVersion`에 `WAITING_ROOM`, `LockType`에 `WAITING_QUEUE` 추가. `index.html` badge 삼항식에 `WAITING_ROOM` 분기 + `.badge-waiting`(teal) CSS 추가(기존엔 잘못된 `badge-v6` fallback).
5. ✅ **[수정완료] `ticketToken` DB 유니크 제약**. `Reservation.ticketToken`에 `@Column(unique = true)` 추가 → V6 멱등성의 DB 최후 방어선(컨슈머 다중화/at-least-once 중복 전달 대비). MySQL은 NULL을 중복으로 보지 않아 V1~V5·WaitingRoom의 다중 NULL은 그대로 허용.
   - ⚠️ **운영 주의**: `ddl-auto: update`는 기존 컬럼에 유니크 제약을 추가하지 못한다(Hibernate 한계). 신규/재생성 스키마에서만 인덱스(`UNIQUE KEY ... (ticket_token)`)가 생성됨 → 기존 환경엔 별도 마이그레이션(ALTER) 필요. 로컬에선 테이블 재생성으로 적용·검증 완료(NULL 다중 허용 + 중복 non-null `ERROR 1062` 거부 실증).
6. ℹ️ **[#5로 해소] V6 멱등성 의존성**. 과거엔 단일 파티션(concurrency=1) + 앱 레벨 `existsByTicketToken`에만 의존했으나, #5의 DB 유니크 제약으로 컨슈머 다중화 시에도 중복 INSERT가 DB에서 차단됨.
7. ⏳ **[미조치] WaitingRoom 토큰 SoldOut 시 소비**. `removeFromProcessing`이 DECR 직후·SoldOut 판정 전에 호출되어 매진 실패 시에도 토큰이 소비됨(실패 후 동일 토큰 재시도 불가). 이번 수정 범위 밖.
8. ℹ️ **[미조치] `PaymentService.findReservationById`가 예약 미존재 시 `ConcertNotFoundException`을 던짐** — 의미상 부정확(메시지가 concertId 기준). 이번 수정 범위 밖.

---

## 14. 결론 — 버전별 트레이드오프 한 줄 정리

| 버전 | 정합성 | 응답 | 처리량 | 운영복잡도 | 핵심 한계 |
|---|---|---|---|---|---|
| V1 | ✗(오버부킹) | 즉시 | 최고 | 최저 | Race Condition(@Version 일부 방어) |
| V2 | ✓ | 즉시 | 낮음 | 낮음 | 커넥션 점유·데드락 |
| V3 | ✓ | 즉시 | 중 | 중 | 경합 심하면 Retry Storm |
| V4 | △ | 즉시 | 중상 | 중상 | 워치독 없음 → 실행 중 TTL 만료 시 오버부킹 |
| V5 | ✓ | 즉시 | 상 | 상 | Redis 의존, 워치독 설정 함정(leaseTime 명시 금지) |
| V6 | ✓ | **PENDING(비동기)** | 최상 | 최상 | 실시간 결과 불가, Kafka/Outbox 인프라 |
| WaitingRoom | ✓ | 즉시 SUCCESS | 최상 | 최상 | 결과적 일관성, 대기열 운영 필요 |

- **일반 티켓팅 권장**: V5(Redisson) — 즉시 응답 + 적정 성능.
- **폭발적 트래픽 권장**: WaitingRoom — 처리열 정원 밖 트래픽을 DB/Redis 접근 없이 즉시 차단해 **서버 자체를 보호**.
