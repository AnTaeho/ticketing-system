# 티켓팅 동시성 제어 포트폴리오

대용량 트래픽 환경의 **동시성 이슈를 7가지 전략으로 해결**하고, 각 방식의 성능·트레이드오프를 **Gatling 실측 데이터**로 비교한 백엔드 포트폴리오입니다.

> "Redis 썼습니다"가 아닌, **왜 그 락을 선택했는지 근거를 데이터로 제시**합니다.

---

## 프로젝트 개요

| 항목 | 내용 |
|------|------|
| **목표** | 동시성 제어 방식별 정합성·성능 실측 비교 |
| **타겟 직무** | 백엔드 (Java / Spring) |
| **시나리오** | 100장 티켓에 수천 명이 동시 접속하는 극한 경합 |
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
| V7 | Redis 선점 + 대기열 | Sorted Set 대기열(정원 200) + DECR 선점 + 비동기 DB |
| V5CB | Circuit Breaker | Resilience4j — Redis 장애 시 V2 자동 폴백 |

**기술 스택**: Java 17 · Spring Boot 4.0.6 · JPA/Hibernate 7.x · MySQL 8.x · Redis 7.x(Lettuce, Redisson 3.50) · Kafka 3.x · Gatling 3.9 · Thymeleaf + Chart.js · Gradle

---

## 아키텍처

```
클라이언트 → Spring Boot Controller
    ├── V1~V3: JPA → MySQL (DB 레벨 락)
    ├── V4~V5: Redis 분산 락 → MySQL (V4 Lettuce 스핀 / V5 Redisson Pub-Sub)
    ├── V5CB:  V5 → [Circuit Breaker] → MySQL / Redis 장애 시 V2 자동 폴백
    ├── V6:    Redis DECR 선점 → 즉시 PENDING+토큰 → Kafka → 멱등 Consumer → MySQL
    └── V7:    Sorted Set 대기열 → DECR 선점 → 즉시 SUCCESS/FAIL → 비동기 DB
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
- **Outbox 패턴**: 커밋 직후(`@TransactionalEventListener AFTER_COMMIT`) Kafka 발행 + 5분 주기 미발행 복구 → at-least-once.
- **단일 파티션 Consumer**: 멱등(`existsByTicketToken`) 예약 INSERT만 수행. **재고 SSOT = Redis**(재검증 X).
- **DLT**: 처리 불가 메시지는 2회 재시도 후 `<topic>.DLT` 격리 → 컨슈머 정지(HoL) 방지. ack는 DB 커밋 후 등록.

### V7 — Redis 선점 + 대기열
동시 처리 인원을 `PROCESSING_QUEUE_SIZE=200`으로 묶어 부하를 통제하고 FIFO 공정성 확보.
- **입장 판정/승격을 Lua로 원자화** → 정원 초과 입장(TOCTOU) 제거.
- Sorted Set `score = currentTimeMs + TTL` → FIFO + TTL 만료를 단일 자료구조로.
- `QueueScheduler` 3초마다 만료 토큰 제거 + 대기→처리 승격.
- `/v7/demo` SSE로 대기열/처리열/재고 실시간 시각화(데모 전용).

> **V6 vs V7**: V6은 처리량 최적화, V7은 "제어 가능한 처리량 + FIFO 공정성". 운영자가 트래픽 규모로 선택.

### V5CB — Circuit Breaker + Graceful Degradation
```
[Circuit Breaker]
 ├── CLOSED (정상):  V5 Redisson → MySQL
 ├── OPEN   (장애):  즉시 폴백 → V2 Pessimistic Lock → MySQL
 └── HALF_OPEN:      소수 요청 통과 → 성공 시 CLOSED 복귀
```
인프라 예외(Redis 계열)만 폴백, 그 외 예외는 재던짐. `ChaosAspect`로 Redis 차단 장애 주입 테스트.

---

## 부하 테스트 결과

### 시나리오 A — 극한 경합 (재고 100장, 동시 1,000명)

| 버전 | 방식 | P99 | Mean | TPS | 오버부킹 |
|------|------|-----|------|-----|----------|
| V1 | No Lock | 407ms | 273ms | 1,000 | 0 (lost update) |
| V2 | Pessimistic | 338ms | 228ms | 1,000 | 0 ✅ |
| V3 | Optimistic | 338ms | 252ms | 1,000 | 0 ✅ |
| V4 | Spin Lock | **2,966ms** | 627ms | 250 | 0 ✅ |
| V5 | Redisson | 731ms | 451ms | 1,000 | 0 ✅ |
| V6 | Kafka | 155ms | 101ms | 1,000 | 0 ✅ |

> V4 TPS 급감: `Thread.sleep(100ms)` 스핀 대기가 쓰레드 점유 → 동시 처리 수 제한

### 시나리오 B — 실제 플로우 (재고 100,000장, 동시 2,000명, think time 포함)

| 버전 | 방식 | P99 | req/s | 에러율 |
|------|------|-----|-------|--------|
| V1 | No Lock | 5,039ms | 262 | 5.1% |
| V2 | Pessimistic | 7,194ms | 222 | 0% |
| V3 | Optimistic | 7,359ms | 216 | 0% |
| V4 | Spin Lock | **13,731ms** | 236 | 0% |
| V5 | Redisson | 8,538ms | 241 | 0% |
| V6 | Kafka | **2ms** | 400 | 0% |

### V5CB — Circuit Breaker (CHAOS=redis_block, 500명)

| 시나리오 | P99 | Mean | TPS | 에러율 | fallbackRatio |
|---------|-----|------|-----|--------|---------------|
| CLOSED (정상) | 1,150ms | 755ms | 661 | 0% | 0% |
| OPEN → V2 폴백 | 411ms | 257ms | 1,946 | 0% | 100% |

> 장애 상황에서도 에러율 0% — CB가 V2로 자동 폴백하여 서비스 중단 없음

---

## 대시보드

`http://localhost:8080/dashboard` — Thymeleaf + Chart.js 실시간 성능 비교.

![대시보드 전체 뷰](docs/images/dashboard-full.png)

- 시나리오(A/B) · 버전 · 동시 사용자 수 필터
- TPS / P99 / 오버부킹 / 에러율 차트 + 결과 테이블
- **Circuit Breaker 탭**: Redis 경로 vs V2 폴백 분포 + TPS/에러율 비교

---

## 실행 방법

**사전 요구사항**: Java 17+ · MySQL 8.x(`ticketing` DB) · Redis 7.x · Docker(Kafka)

```bash
docker-compose -f kafka-docker-compose.yml up -d              # 1. Kafka 기동
./gradlew bootRun                                             # 2. 앱 실행 (application.yml의 MySQL 비번 수정)

# 3. 부하 테스트 (재고 reset 후 실행)
cd load-test/gatling
curl -X POST "http://localhost:8080/api/concerts/1/reset?stock=100"
mvn gatling:test -Dgatling.simulationClass=ScenarioASimulation -DVERSION=v5 -DUSERS=1000
```

---

## 핵심 트레이드오프

```
정합성:    V2 = V3 = V4 = V5 = V5CB = V6 = V7 >> V1
TPS:       V7 > V6 > V5CB ≈ V5 ≈ V2 ≈ V3 > V4 > V1
P99:       V6 << V5CB ≈ V5 < V2 ≈ V3 << V4
장애내성:  V5CB (자동 폴백) > V5 > V4 > V2 = V3 > V1
운영복잡:  V1 < V2 < V3 < V4 < V5 < V5CB < V6 < V7
```

**결론**: 일반 티켓팅 → **V5**(즉시 응답+균형) · 폭발 트래픽 → **V7**(대기열) · 장애 대응 → **V5CB**(자동 폴백)

---

## 프로젝트 구조

```
src/main/java/com/example/ticketing/
├── concert/                # 공연 엔티티, 재고 관리
├── reservation/
│   ├── controller/         # ReserveControllerV1~V7, V5CB
│   ├── service/            # v1~v7 (TicketServiceVn + TicketTransactionVn), v5cb
│   ├── kafka/              # TicketConsumer, ReservationMessage (V6)
│   └── outbox/             # OutboxEvent, OutboxRelay (V6 발행)
├── queue/                  # QueueRedisRepository, CommandService, Scheduler (V7)
├── demo/                   # V7 실시간 SSE 시각화 (데모 전용)
├── payment/                # 결제 Mock (100~200ms 지연)
├── dashboard/              # TestResult + Chart.js 대시보드
└── global/
    ├── config/             # RedissonConfig, KafkaConfig, ResilienceConfig
    ├── lock/               # LettuceLockRepository (V4)
    ├── stock/              # RedisStockRepository (V6/V7 재고 SSOT)
    ├── resilience/         # CircuitBreakerStatsHolder, RedisInfraFailures
    ├── chaos/              # ChaosAspect (V5CB Redis 장애 주입)
    └── exception/          # GlobalExceptionHandler

load-test/gatling/          # ScenarioA/B, CBSimulation
docs/images/                # 대시보드 스크린샷
```
