# 티켓팅 동시성 제어 포트폴리오

대용량 트래픽 환경의 **동시성 이슈를 7가지 전략으로 해결**하고, 각 방식의 성능·트레이드오프를 **Gatling 실측 데이터**로 비교한 백엔드 포트폴리오입니다.

> "Redis 썼습니다"가 아닌, **왜 그 락을 선택했는지 근거를 데이터로 제시**합니다.

---

## 프로젝트 개요

| 항목 | 내용 |
|------|------|
| **목표** | 동시성 제어 방식별 정합성·성능 실측 비교 |
| **타겟 직무** | 백엔드 (Java / Spring) |
| **시나리오** | 100장 티켓에 500명이 동시 접속하는 극한 경합 |
| **비교 지표** | 오버부킹, TPS, P99, 에러율 |

### 구현한 전략

| 버전 | 방식 | 핵심 기술 |
|------|------|---------|
| V1 | No Lock (기준선) | 동시성 이슈 의도적 발생 |
| V2 | DB 비관적 락 | `SELECT ... FOR UPDATE` |
| V3 | DB 낙관적 락 | `@Version` + 재시도 |
| V4 | Redis 스핀 락 | Lettuce `SETNX` + Spin Wait |
| V5 | Redis Pub-Sub 락 | Redisson `RLock` |
| V6 | Redis 선점 + Outbox + Kafka | DECR 선점 → Outbox 발행 → 멱등 Consumer(+DLT) |
| **WaitingRoom** | Redis 선점 + 대기열 *(별도 모듈)* | Sorted Set 대기열(정원 200) + DECR 선점 + 비동기 DB |

> V1~V6은 "단건 예약을 안전하게 직렬화"하는 같은 문제의 변주다. **WaitingRoom**(구 V7)은 "유입 트래픽 자체를 통제"하는 다른 층위의 문제라, 버전 라인에서 분리해 독립 패키지 `com.example.ticketing.waitingroom`로 두었다.

**기술 스택**: Java 17 · Spring Boot 4.0.6 · JPA/Hibernate 7.x · MySQL 8.x · Redis 7.x(Lettuce, Redisson 3.50) · Kafka 3.x · Gatling 3.9 · Thymeleaf + Chart.js · Gradle

---

## 아키텍처

```
클라이언트 → Spring Boot Controller
    ├── V1~V3: JPA → MySQL (DB 레벨 락)
    ├── V4~V5: Redis 분산 락 → MySQL (V4 Lettuce 스핀 / V5 Redisson Pub-Sub)
    └── V6:    Redis DECR 선점 → 즉시 PENDING+토큰 → Kafka → 멱등 Consumer → MySQL

WaitingRoom (별도 모듈): Sorted Set 대기열 → DECR 선점 → 즉시 PENDING/FAIL → 비동기 DB
```

---

## 버전별 구현 핵심

### V1 — No Lock (기준선)
`조회 → 검증 → 차감 → 저장`을 동기화 없이 수행 → **lost update**로 재고보다 적은 예약 생성. 기준 데이터 확보용.

### V2 — DB 비관적 락
`@Lock(PESSIMISTIC_WRITE)` → `SELECT ... FOR UPDATE`. 트랜잭션 종료까지 대기 → 정합성 100%, 대기 큐 증가.

### V3 — DB 낙관적 락
`@Version` 충돌 시 `OptimisticLockRetryer`로 재시도(지수 백오프+지터). 충돌이 잦은 티켓팅엔 재시도 폭발 → 부적합.

### V4 — Redis Lettuce Spin Lock
```java
public ReserveResponse reserve(Long concertId, Long userId) {
    acquireSpinLock(concertId);                              // @Transactional 밖
    try { return transaction.reserveInTransaction(concertId, userId); }  // 별도 빈
    finally { lettuceLockRepository.releaseLock(concertId); }            // 커밋 후 해제 보장
}
```
100ms 간격 폴링 → Redis 부하·꼬리 레이턴시 증가. UUID 소유자 검증 + Lua compare-and-delete로 비소유자 락 삭제 방지.

### V5 — Redis Redisson Pub-Sub Lock
`RLock.tryLock(waitTime)` — 락 해제 시 Pub-Sub 통보로 폴링 제거 → Redis 부하 감소, TPS 향상. 워치독 자동 갱신(leaseTime 미지정).

### V6 — Redis 선점 + Outbox + Kafka 비동기
```java
@Transactional
public ReserveResponse reserve(Long concertId, Long userId) {
    long remaining = redisStockRepository.decrement(concertId);   // 원자적 선점
    if (remaining < 0) { redisStockRepository.increment(concertId); throw new SoldOutException(concertId); }
    OutboxEvent event = outboxEventRepository.save(OutboxEvent.create(...));  // DB 트랜잭션과 원자적 저장
    eventPublisher.publishEvent(new OutboxCreatedEvent(event.getId()));
    return new ReserveResponse(null, PENDING, ticketToken);       // 즉시 PENDING + 토큰
}
```
- **Outbox 패턴**: 커밋 직후(`@TransactionalEventListener AFTER_COMMIT`) Kafka 발행 + 5분 주기 미발행 복구 → at-least-once. Outbox 트랜잭션 롤백이나 최종 발행 실패 시 Redis 재고를 보상한다.
- **단일 파티션 Consumer**: 멱등(`existsByTicketToken`) 예약 INSERT만 수행. **재고 SSOT = Redis**(재검증 X).
- **DLT**: 처리 불가 메시지는 2회 재시도 후 `<topic>.DLT` 격리 → 컨슈머 정지(HoL) 방지. ack는 DB 커밋 후 등록.
- **실패 보상**: Outbox 트랜잭션이 롤백되거나 발행 재시도를 모두 소진하면 선점한 Redis 재고를 복구한다.

**예약 상태 폴링 API**

비동기 처리 결과는 `ticketToken`으로 조회한다.

```
# 1. 예약 요청 → 즉시 PENDING + ticketToken 반환
POST /api/v6/concerts/{concertId}/reserve
{"userId": 1}

→ {"reservationId": null, "status": "PENDING", "ticketToken": "550e8400-e29b-..."}

# 2. 처리 완료 여부 폴링
GET /api/v6/reservations/{ticketToken}/status

→ {"reservationId": null, "status": "PENDING", "ticketToken": "..."}  # Kafka 처리 중
→ {"reservationId": 42,   "status": "SUCCESS", "ticketToken": "..."}  # 처리 완료
→ {"reservationId": null, "status": "FAIL",    "ticketToken": "..."}  # 최종 발행 실패
→ 404 Not Found                                                         # 유효하지 않은 토큰
```

> Reservation이 있으면 `SUCCESS`, Outbox가 `FAILED`이면 `FAIL`, 그 외 Outbox 상태는 `PENDING`이다. 토큰이 두 테이블 모두 없으면 404를 반환한다.

### WaitingRoom — Redis 선점 + 대기열 *(별도 모듈, 구 V7)*
독립 패키지 `com.example.ticketing.waitingroom`. 동시 처리 인원을 `PROCESSING_QUEUE_SIZE=200`으로 묶어 부하를 통제하고 FIFO 공정성 확보.
- **입장 판정/승격을 Lua로 원자화** → 정원 초과 입장(TOCTOU) 제거.
- Sorted Set `score = currentTimeMs + TTL` → FIFO + TTL 만료를 단일 자료구조로.
- `QueueScheduler` 3초마다 만료 토큰 제거 + 대기→처리 승격.
- 예약 `POST /api/waitingroom/concerts/{id}/reserve`는 비동기 저장 전 `PENDING`을 반환하고, 토큰은 `POST /api/waitingroom/concerts/{id}/queue/token`으로 발급한다. 비동기 저장은 `saveAndFlush()`로 DB 오류를 감지하고 실패 시 Redis 재고를 복구한다.

> **V6 vs WaitingRoom**: V6은 처리량 최적화, WaitingRoom은 "제어 가능한 처리량 + FIFO 공정성". 운영자가 트래픽 규모로 선택.

---

## 부하 테스트 결과

### 시나리오 A — 극한 경합 (재고 100장, 동시 500명)

| 버전 | 방식 | P99 | Mean | TPS | 오버부킹 |
|------|------|-----|------|-----|----------|
| V1 | No Lock | 342ms | 226ms | 500 | 0 (lost update) |
| V2 | Pessimistic | 293ms | 233ms | 500 | 0 ✅ |
| V3 | Optimistic | 286ms | 211ms | 500 | 0 ✅ |
| V4 | Spin Lock | **3,587ms** | 895ms | 100 | 0 ✅ |
| V5 | Redisson | 508ms | 350ms | 500 | 0 ✅ |
| V6 | Kafka | 402ms | 335ms | 500 | 0 ✅ |

> V4 TPS 급감: `Thread.sleep(100ms)` 스핀 대기가 쓰레드 점유 → 동시 처리 수 제한

### 시나리오 B — 실제 플로우 (재고 10,000장, 동시 2,000명, think time 포함)

| 버전 | 방식 | P99 | req/s | 에러율 |
|------|------|-----|-------|--------|
| V1 | No Lock | 4,706ms | 267.4 | 6.85% |
| V2 | Pessimistic | 9,934ms | 222.2 | 0% |
| V3 | Optimistic | 12,458ms | 150.9 | 0% |
| V4 | Spin Lock | **13,731ms** | 223.8 | 0% |
| V5 | Redisson | 8,670ms | 247.3 | 0% |
| V6 | Kafka | **125ms** | 400 | 0% |

---

## 대시보드

`http://localhost:8080/dashboard` — Thymeleaf + Chart.js 실시간 성능 비교.

- 시나리오(A/B) · 버전 · 동시 사용자 수 필터
- TPS / P99 / 오버부킹 / 에러율 차트 + 결과 테이블

---

## 실행 방법

**사전 요구사항**: Java 17+ · MySQL 8.x(`ticketing` DB) · Redis 7.x · Docker(Kafka). 전체 통합 테스트도 세 인프라가 모두 실행 중이어야 한다.

```bash
docker compose -f kafka-docker-compose.yml up -d              # 1. Kafka 기동
cp src/main/resources/application-local.yml.example \
   src/main/resources/application-local.yml               # 2. 로컬 설정 복사 후 DB 비밀번호 수정
./gradlew bootRun                                             # 3. 앱 실행 (default profile: local)

# 전체 통합 테스트
./gradlew test

# 4. 부하 테스트 (재고 reset 후 실행)
cd load-test/gatling
curl -X POST "http://localhost:8080/api/concerts/1/reset?stock=100"
mvn gatling:test -Dgatling.simulationClass=ScenarioASimulation -DVERSION=v5 -DUSERS=500

# V6 비동기 예약 + 폴링 예시
curl -X POST "http://localhost:8080/api/v6/concerts/1/reserve" \
     -H "Content-Type: application/json" -d '{"userId": 1}'
# → {"status":"PENDING","ticketToken":"550e8400-..."}

curl "http://localhost:8080/api/v6/reservations/550e8400-.../status"
# → {"reservationId":42,"status":"SUCCESS","ticketToken":"550e8400-..."}
```

---

## 핵심 트레이드오프

```
정합성:    V2 = V3 = V4 = V5 = V6 = WaitingRoom >> V1
TPS:       WaitingRoom > V6 > V5 ≈ V2 ≈ V3 > V4 > V1
P99:       V6 << V5 < V2 ≈ V3 << V4
운영복잡:  V1 < V2 < V3 < V4 < V5 < V6 < WaitingRoom
```

**결론**: 일반 티켓팅 → **V5**(즉시 응답+균형) · 폭발 트래픽 → **WaitingRoom**(대기열, 별도 모듈)

---

## 프로젝트 구조

```
src/main/java/com/example/ticketing/
├── concert/                # 공연 엔티티, 재고 관리
├── reservation/
│   ├── controller/         # ReserveControllerV1~V6
│   ├── service/            # v1~v6 (TicketServiceVn + TicketTransactionVn)
│   ├── kafka/              # TicketConsumer, ReservationMessage (V6)
│   └── outbox/             # OutboxEvent, OutboxRelay (V6 발행)
├── waitingroom/            # 대기열 예매 (구 V7, 별도 모듈)
│   ├── controller/         # ReservationController, QueueController
│   ├── service/            # ReservationService, ReservationTransaction, QueueCommand/QueryService
│   ├── repository/         # QueueRedisRepository
│   ├── scheduler/          # QueueScheduler (만료 제거 + 승격)
│   └── dto/                # ReserveRequest, Queue*Response
├── payment/                # 결제 Mock (100~200ms 지연)
├── dashboard/              # TestResult + Chart.js 대시보드
└── global/
    ├── config/             # RedissonConfig, KafkaConfig
    ├── lock/               # LettuceLockRepository (V4)
    ├── stock/              # RedisStockRepository (V6/WaitingRoom 재고 SSOT)
    └── exception/          # GlobalExceptionHandler

load-test/gatling/          # ScenarioA/B
```
