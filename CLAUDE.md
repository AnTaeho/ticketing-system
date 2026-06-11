# 티켓팅 동시성 제어 포트폴리오 — CLAUDE.md

## 프로젝트 개요

**목표**: 대용량 트래픽 환경에서 발생하는 동시성 이슈를 다양한 락 전략으로 해결하고, 각 방식의 성능과 트레이드오프를 실측 데이터로 비교한다.

**타겟 직무**: 백엔드 (Java / Spring) — 중견/대기업

**핵심 어필 포인트**:
- 단순히 Redis 썼다가 아닌, **왜 그 락을 선택했는지 근거**를 데이터로 제시
- 버전별 부하 테스트로 **정량적 비교** 가능
- 동시성 문제를 처음부터 설계하고 개선한 전 과정 기록

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| Language | Java 17 |
| Framework | Spring Boot 4.0.6 |
| ORM | Spring Data JPA / Hibernate 7.x |
| DB | MySQL 8.x |
| Cache / Lock | Redis (Lettuce, Redisson 3.50.0) |
| Message Queue | Kafka |
| 부하 테스트 | Gatling |
| 결과 시각화 | Thymeleaf + Chart.js |
| 빌드 | Gradle |

---

## 프로젝트 구조

```
ticketing-system/
├── src/main/java/com.example.ticketing/
│   ├── concert/
│   ├── reservation/
│   │   ├── controller/  ReserveControllerV1.java ~ V6.java
│   │   └── service/     TicketServiceV1.java ~ V6.java, TicketServiceV5CB.java
│   ├── payment/
│   ├── dashboard/       TestResult, DashboardController
│   └── global/          config/, exception/
├── src/main/resources/
│   ├── templates/dashboard/index.html
│   └── static/js/dashboard.js
├── load-test/gatling/   ScenarioA/B/CBSimulation.scala
└── docs/
    ├── knowledge/       level-1 ~ level-13 마크다운
    ├── performance-report.md
    └── tradeoff-summary.md
```

---

## 버전별 구현 전략

### V1 — No Lock (기준선)
순수 JPA `save()`, 동기화 없음. 재고 조회 → 감소 → 저장 사이 Race Condition 의도적 발생.

### V2 — DB 비관적 락 (Pessimistic Lock)
`@Lock(LockModeType.PESSIMISTIC_WRITE)` → `SELECT ... FOR UPDATE`. 트랜잭션 종료까지 대기.

### V3 — DB 낙관적 락 (Optimistic Lock)
`@Version` + `ObjectOptimisticLockingFailureException` 시 재시도. `OptimisticLockRetryer(maxRetry=10)` 구현.

### V4 — Redis 분산 락 (Lettuce / Spin Lock)
`SET key uuid NX EX 3` + 100ms 간격 스핀 대기. `@Transactional`은 내부 메서드에만 적용하여 커밋 후 락 해제 보장.

### V5 — Redis 분산 락 (Redisson / Pub-Sub)
`RLock.tryLock(waitTime=5s, leaseTime=3s)`. 락 해제 시 Pub-Sub으로 대기 스레드 알림 → 스핀 폴링 제거.

### V5CB — Redisson + Circuit Breaker (Resilience4j)
Redis 장애 시 `CLOSED → OPEN → HALF_OPEN` 전환. 폴백 체인: Redis 락 실패 → V2 비관적 락. `ChaosAspect`로 장애 주입 테스트.

### V6 — Kafka 비동기 대기열
예약 요청을 Kafka 토픽 발행 → PENDING 즉시 응답. 단일 파티션 컨슈머가 순차 처리 → Redis에 결과 저장 → 폴링 API로 확인.

### V7 — Redis 선점 + 대기열 (미구현)
Redis Sorted Set 대기열 → DECR 원자적 재고 선점 → 성공자만 비동기 DB 처리. 타임아웃 시 재고 복구.

---

## 부하 테스트 시나리오

### 시나리오 A — 극한 경합 (정합성 검증)
| 항목 | 값 |
|------|-----|
| 티켓 재고 | 100장 |
| 동시 사용자 수 | 500 / 1,000 / 2,000 |
| 요청 유형 | `POST /api/v{n}/concerts/1/reserve` 단건 |

핵심 지표: **오버부킹 건수** (정합성), P99 응답시간, 에러율

### 시나리오 B — 처리량 측정 (실제 플로우)
| 항목 | 값 |
|------|-----|
| 티켓 재고 | 100,000장 |
| 동시 사용자 수 | 500 / 1,000 / 2,000 |
| 요청 유형 | 공연 목록 → 상세 → 예약 → 결제 (4단계 플로우, think time 포함) |

핵심 지표: **TPS** (플로우 완결 기준), P99 응답시간

**테스트 전 필수**: `POST /api/concerts/1/reset?stock={n}` 으로 재고 초기화.

**결과 저장**: `POST /api/test-results` 로 수동 저장 (version, lockType, scenarioType, concurrentUsers, initialStock 필수).

---

## 트레이드오프 요약

```
정합성만 보면: V2 ~ V7 모두 통과
성능만 보면:   V7 > V6 > V5 > V4 > V3 > V2 > V1
실시간 응답:   V1~V5, V7 가능 / V6 불가 (비동기)
운영 복잡도:   V1 < V2 < V3 < V4 < V5 < V6 < V7
서버 보호:     V7 (대기열) > V6 > V5 > V4 > V3 > V2 > V1
```

**결론**:
- **일반 티켓팅**: V5 (Redisson) — 즉시 응답 + 적정 성능의 균형
- **폭발적 트래픽**: V7 (Redis 선점 + 대기열) — 운영자 사전 설정으로 전환
- **장애 대응**: V5CB (Circuit Breaker) — Redis 장애 시 V2로 자동 폴백

---

## 버전별 함정 & 수정 이력

> 프레젠테이션(프레젠테이션2.pdf) 분석 후 발견된 실제 코드 함정과 수정 내용.

| # | 버전 | 함정 | 파일 | 수정 방향 |
|---|------|------|------|-----------|
| 1 | 전체 | `@Version` 측정 왜곡 — Concert 엔티티의 `@Version`이 V1에도 적용되어 순수 무방비 기준선이 아님 | `Concert.java:25` | 문서화 (의도된 다층 방어로 기록, V1 오버부킹 수치가 실제보다 낮게 나올 수 있음) |
| 2 | V3 | 고정 50ms sleep → Thundering Herd — 동시 충돌 스레드가 정확히 50ms 뒤 재충돌 | `OptimisticLockRetryer.java:30` | 지수 백오프 + 지터 (`50ms × 2^retry + random`) |
| 3 | V4 | 비소유자 락 삭제 — TTL 만료 후 A의 `finally`가 B의 락을 삭제 → C 진입(정합성 파괴) | `LettuceLockRepository.java:24` | UUID 소유자 검증 + Lua compare-and-delete 원자 실행 |
| 4 | V5 | `leaseTime` 명시 → 워치독 비활성화 — `tryLock(5, 3, SEC)` 로 leaseTime=3초 명시 시 워치독 꺼짐 | `TicketServiceV5.java:38` | `leaseTime` 제거 → `tryLock(waitTime, unit)` 2인자 버전으로 워치독 자동 갱신 활성화 |
| 5 | V5CB | `RedisConnectionException` 집계 누락 — 서버 다운 시 해당 예외는 CB가 카운트 안 함 → CLOSED 유지 | `ResilienceConfig.java:24` | `recordException`에 `RedisConnectionException` 추가 + 중첩 cause 순회 |
| 6 | V5CB | `catch(Throwable)` 위험 — NPE 등 코드 버그도 조용히 V2 폴백으로 삼킴 | `TicketServiceV5CB.java:36` | 인프라 예외(Redis 계열)만 폴백, 그 외 미지 예외는 재던짐 |
| 7 | V6 | ack 순서 역전 — `ack.acknowledge()`가 `@Transactional` 커밋 전에 호출 → DB 실패 시 오프셋 이미 넘어가 메시지 영구 유실 | `TicketConsumer.java:39` | `TransactionSynchronization.afterCommit()` 콜백 뒤로 이동 |
| 8 | V6 | `ticketToken` 미반환 — PENDING 응답에 조회 핸들 없어 폴링 API 사용 불가 | `TicketServiceV6.java:49` | `ReserveResponse`에 `ticketToken` 필드 추가 후 응답에 포함 |

---

## Phase 완료 체크리스트

```
[x] Phase 0: 프로젝트 초기 세팅 (Gradle, yml, Kafka docker-compose)
[x] Phase 1: 공통 도메인 / API 뼈대 (Concert, Reservation, Payment 엔티티 + 공통 API)

[x] Phase 2: V1 No Lock — TicketServiceV1, 오버부킹 발생 확인
[x] Phase 3: V2 Pessimistic Lock — TicketServiceV2, 데드락 시나리오 테스트
[x] Phase 4: V3 Optimistic Lock — OptimisticLockRetryer, TicketServiceV3
[x] Phase 5: V4 Lettuce Spin Lock — LettuceLockRepository, TicketServiceV4
[x] Phase 6: V5 Redisson Pub-Sub — TicketServiceV5, V4 vs V5 Redis 부하 비교
[x] Phase 7: V6 Kafka Queue — TicketProducer, TicketConsumer, 폴링 API

[x] Phase V5CB-1~11: Circuit Breaker 전체
    - Resilience4j Bean + ResilienceConfig
    - CircuitBreakerStatsHolder (관찰 가능성)
    - TicketServiceV5CB (Redis→V2 폴백 체인)
    - ReserveControllerV5CB + ChaosController + ChaosAspect
    - StatsController (/api/v5cb/stats, /reset)
    - TestResult fallbackCount/cbTripCount 컬럼 추가
    - 대시보드 Circuit Breaker 탭 (차트 3종)
    - CBSimulation.scala (CHAOS=none / redis_block)
    - Gatling 결과 2건 DB 저장 (id=38 정상, id=39 폴백)
    - docs/knowledge/level-12-circuit-breaker.md

[x] Phase 8: V7 Redis 선점 + 대기열
    - 8-1: Queue 인프라 (QueueRedisRepository, CommandService, QueryService, Scheduler, Controller)
           - UUID 토큰 / Redis Sorted Set / PROCESSING_QUEUE_SIZE=200
           - score = currentTimeMs + TTL → FIFO + 자동 만료
    - 8-2: Redis DECR 재고 선점 → 즉시 SUCCESS/FAIL 응답 (TicketServiceV7)
    - 8-3: @Async DB 처리 (TicketTransactionV7) → DB 실패 시 Redis 재고 복구
    - 8-4: 스케줄러 3초마다 만료 토큰 제거 → 대기→처리 자동 승격
    - 8-5: V5 vs V7 부하 테스트 비교 (미완료)

[ ] Phase 9: 전 버전 시나리오 A + B 부하 테스트 완료 + 결과 DB 저장

[x] Phase 10: 대시보드 (TestResult, DashboardController, 차트 4종 + 필터 + 테이블)
[x] Phase 11: docs/performance-report.md (V1~V6 + V5CB 실측 수치)
[x] Phase 12: README.md 완성 (V5CB 섹션 + 대시보드 스크린샷)
```

---

## 코딩 컨벤션 & 규칙

> Claude가 코드를 작성할 때 반드시 따라야 하는 규칙. 기능 동작보다 우선순위가 높다.

### 가독성 원칙

**메서드는 한 가지 일만 한다** — 조회 + 검증 + 저장을 동시에 하면 안 됨. 메서드 길이 20줄 초과 시 분리.

**메서드 이름은 동작을 설명한다**
```java
// Bad:  public void process(Long id)
// Good: public Reservation reserveTicket(Long concertId, Long userId)
//       public void decreaseStock(Concert concert)
//       public void validateStockAvailable(Concert concert)
```

**변수 이름은 의미를 담는다**
```java
// Bad:  Concert c = ...; int n = c.getStock();
// Good: Concert concert = ...; int remainingStock = concert.getStock();
```

**매직 넘버는 상수로 뺀다**
```java
private static final long SPIN_WAIT_MS  = 100;
private static final long LOCK_WAIT_SEC = 5;
private static final long LOCK_LEASE_SEC = 3;
```

**조건문은 의미 있는 메서드로 추출한다**
```java
private void validateStockAvailable(Concert concert) {
    if (concert.isOutOfStock()) throw new SoldOutException(concert.getId());
}
```

### 복잡도 원칙

- 인터페이스는 구현체가 2개 이상이거나 테스트 대역이 필요할 때만 도입
- 불필요한 레이어(ServiceImpl 패턴 등) 추가 금지
- 커스텀 예외로 의미 부여: `SoldOutException`, `LockAcquisitionFailedException`, `ConcertNotFoundException`
- Lombok: `@Getter`, `@NoArgsConstructor(access = PROTECTED)` 만 허용. `@Data`, `@Setter` 금지.

### 로그 원칙

모든 락 관련 이벤트는 `INFO` 레벨로 출력하여 부하 테스트 분석에 활용한다.

```java
log.info("[V4] 락 획득 성공 - concertId={}", concertId);
log.info("[V3] 낙관적 락 충돌, 재시도 - concertId={}, 시도횟수={}", concertId, retryCount);
log.info("[공통] 재고 차감 - concertId={}, 차감 후 재고={}", concertId, remainingStock);
```

### 구조 원칙

- 컨트롤러는 버전별 URL 라우팅만 담당, 비즈니스 로직 없음
- 모든 예외는 `GlobalExceptionHandler`에서 통일 처리 (`@RestControllerAdvice`)
- 재고 초기화 API (`/reset`)는 `@Profile("!prod")`로 프로덕션 비활성화
- `TestResult` 저장 시 필수값: `version`, `lockType`, `scenarioType`, `concurrentUsers`, `initialStock`
- 대시보드 Chart.js는 CDN(`https://cdn.jsdelivr.net/npm/chart.js`)으로 로드

### 코드 작성 단위 원칙

- **한 번에 하나의 파일만** 작성한다
- 논리적 단계가 2개 이상이면 나눈다 (엔티티 → 확인 → 레포지토리 → 확인 → 서비스)
- 각 단계 완료 시 **"여기까지 확인해주세요"** 멘트를 반드시 남긴다
- 다음 단계는 확인 응답을 받은 후에만 진행한다

---

## 지식 정리 목차 (`docs/knowledge/`)

| Level | 파일 | 핵심 주제 |
|-------|------|----------|
| 1 | level-1-concurrency.md | Race Condition, Lost Update, 오버부킹 |
| 2 | level-2-transaction.md | ACID, 격리 수준 4단계, MVCC, Gap Lock |
| 3 | level-3-pessimistic.md | SELECT FOR UPDATE, 데드락, SKIP LOCKED |
| 4 | level-4-optimistic.md | @Version, Retry Storm, ABA 문제 |
| 5 | level-5-lettuce.md | SETNX 원자성, Spin Lock, Lua Script |
| 6 | level-6-redisson.md | Pub-Sub 구조, Watchdog, Redlock |
| 7 | level-7-transactional.md | Self-Invocation, 락+트랜잭션 순서, REQUIRES_NEW |
| 8 | level-8-kafka.md | 단일 파티션 순차 처리, Outbox Pattern, DLT |
| 9 | level-9-springboot4.md | Kafka 자동설정 제거, Jackson 3.x 패키지 변경 |
| 10 | level-10-gatling.md | atOnceUsers vs rampUsers, P99 해석, V4 꼬리 레이턴시 |
| 11 | level-11-interview.md | 면접 Q&A 16개 전체 정리 |
| 12 | level-12-circuit-breaker.md | CB 3상태, Resilience4j 설정, 폴백 체인, 실측 수치 |
| 13 | level-13-redis-stock-management.md | DECR 원자성, 음수 보상, ZSet 대기열, Eventually Consistent |
