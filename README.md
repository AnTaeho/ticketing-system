# 티켓팅 동시성 제어 포트폴리오

대용량 트래픽 환경에서 발생하는 **동시성 이슈를 6가지 락 전략으로 해결**하고,  
각 방식의 성능과 트레이드오프를 **Gatling 실측 데이터**로 비교한 백엔드 포트폴리오입니다.

> 단순히 "Redis 썼습니다"가 아닌, **왜 그 락을 선택했는지 근거를 데이터로 제시**합니다.

---

## 목차

1. [프로젝트 개요](#프로젝트-개요)
2. [기술 스택](#기술-스택)
3. [아키텍처](#아키텍처)
4. [버전별 구현 전략](#버전별-구현-전략)
5. [부하 테스트 결과](#부하-테스트-결과)
6. [대시보드](#대시보드)
7. [실행 방법](#실행-방법)
8. [핵심 트레이드오프](#핵심-트레이드오프)
9. [면접 예상 질문](#면접-예상-질문)
10. [지식 정리 문서](#지식-정리-문서)

---

## 프로젝트 개요

| 항목 | 내용 |
|------|------|
| **목표** | 동시성 제어 방식별 정합성·성능 실측 비교 |
| **타겟 직무** | 백엔드 (Java / Spring) |
| **시나리오** | 100장 티켓에 수천 명이 동시 접속하는 극한 경합 상황 |
| **비교 지표** | 오버부킹 건수, TPS, P99 응답시간, 에러율 |

### 구현한 락 전략

| 버전 | 방식 | 핵심 기술 |
|------|------|---------|
| V1 | No Lock (기준선) | 동시성 이슈 의도적 발생 |
| V2 | DB 비관적 락 | `SELECT ... FOR UPDATE` |
| V3 | DB 낙관적 락 | `@Version` + 재시도 |
| V4 | Redis 스핀 락 | Lettuce `SETNX` + Spin Wait |
| V5 | Redis Pub-Sub 락 | Redisson `RLock` |
| V6 | Redis 선점 + Kafka 비동기 DB | Redis DECR 즉시 SUCCESS/FAIL + Kafka DB 처리 |

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| Language | Java 17 |
| Framework | Spring Boot 4.0.6 (Spring Framework 7.x) |
| ORM | Spring Data JPA / Hibernate 7.x |
| DB | MySQL 8.x |
| Cache / Lock | Redis 7.x (Lettuce, Redisson 3.50.0) |
| Message Queue | Apache Kafka 3.x |
| 부하 테스트 | Gatling 3.9.5 |
| 결과 시각화 | Thymeleaf + Chart.js |
| 빌드 | Gradle |

---

## 아키텍처

```
클라이언트
    │
    ▼
Spring Boot (V1~V6 Controller)
    │
    ├── V1~V3: JPA → MySQL (DB 레벨 락)
    │
    ├── V4~V5: Redis → MySQL (애플리케이션 레벨 분산 락)
    │         V4: Lettuce SETNX Spin Lock
    │         V5: Redisson Pub-Sub Lock
    │
    └── V6: Redis DECR (즉시 SUCCESS/FAIL 반환)
              └── 성공 시 → Kafka Topic → Single Consumer → MySQL (비동기 DB 저장)
```

### 도메인 기반 패키지 구조

```
src/main/java/com/example/ticketing/
├── concert/          # 공연 도메인 (재고 관리)
├── reservation/
│   ├── controller/   # V1~V6 각 버전별 컨트롤러
│   ├── service/      # V1~V6 각 버전별 서비스 (락 전략 구현)
│   └── kafka/        # Kafka Producer / Consumer (V6)
├── payment/          # 결제 도메인 (Mock, 100~200ms 지연)
├── dashboard/        # 성능 결과 저장 + Thymeleaf 대시보드
└── global/
    ├── config/       # RedissonConfig, KafkaConfig
    └── exception/    # GlobalExceptionHandler
```

---

## 버전별 구현 전략

### V1 — No Lock (기준선)

동시성 이슈를 **의도적으로 발생**시켜 기준 데이터를 확보합니다.

```java
// TicketServiceV1.java
@Transactional
public ReserveResponse reserveTicket(Long concertId, Long userId) {
    Concert concert = findConcertById(concertId);
    validateStockAvailable(concert);   // race condition 발생 지점
    decreaseStock(concert);
    return saveReservation(concert, userId);
}
```

**결과**: `lost update` — 다수의 쓰기가 서로를 덮어씀 → 재고보다 적은 예약 생성

---

### V2 — DB 비관적 락

```java
// ConcertRepository.java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT c FROM Concert c WHERE c.id = :id")
Optional<Concert> findByIdWithPessimisticLock(@Param("id") Long id);
```

```sql
-- 실제 실행 쿼리
SELECT * FROM concert WHERE id = 1 FOR UPDATE;
```

**특징**: 트랜잭션 종료까지 다른 트랜잭션이 대기 → 정합성 100%, 대기 큐 증가

---

### V3 — DB 낙관적 락

```java
// Concert.java
@Version
private Long version;   // 충돌 시 ObjectOptimisticLockingFailureException

// OptimisticLockRetryer.java
public <T> T executeWithRetry(Supplier<T> action, int maxRetry) {
    for (int attempt = 1; attempt <= maxRetry; attempt++) {
        try { return action.get(); }
        catch (ObjectOptimisticLockingFailureException e) {
            log.info("[V3] 충돌, 재시도 - 시도={}", attempt);
        }
    }
    throw new ReservationFailedException();
}
```

**특징**: 충돌이 드물 때 유리 / 충돌이 많으면 재시도 폭발 → **티켓팅에 부적합**

---

### V4 — Redis Lettuce Spin Lock

```java
// TicketServiceV4.java (트랜잭션 분리 핵심)
public ReserveResponse reserve(Long concertId, Long userId) {
    acquireSpinLock(concertId);             // ← @Transactional 밖
    try {
        return transaction.reserveInTransaction(concertId, userId);  // ← 별도 빈, @Transactional
    } finally {
        lettuceLockRepository.releaseLock(concertId);  // 트랜잭션 커밋 후 해제 보장
    }
}

private void acquireSpinLock(Long concertId) {
    while (!lettuceLockRepository.tryLock(concertId)) {
        Thread.sleep(SPIN_WAIT_MS);  // 100ms 간격 폴링
    }
}
```

**특징**: 락 해제 시까지 Redis에 지속 폴링 → 부하 증가, 꼬리 레이턴시 폭발

---

### V5 — Redis Redisson Pub-Sub Lock

```java
// TicketServiceV5.java
public ReserveResponse reserve(Long concertId, Long userId) {
    RLock lock = redissonClient.getLock("lock:concert:" + concertId);
    // 락 해제 이벤트 구독 → 불필요한 폴링 제거
    if (!lock.tryLock(LOCK_WAIT_SEC, LOCK_LEASE_SEC, SECONDS)) {
        throw new LockAcquisitionFailedException(concertId);
    }
    try {
        return transaction.reserveInTransaction(concertId, userId);  // 별도 빈, @Transactional
    } finally {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }
}
```

**특징**: 락 해제 시 Pub-Sub 이벤트로 대기 쓰레드에 통보 → Redis 부하 감소, TPS 향상

---

### V6 — Redis 선점 + Kafka 비동기 DB

```java
// TicketServiceV6.java — Redis DECR로 즉시 재고 선점
@Override
public ReserveResponse reserve(Long concertId, Long userId) {
    long remaining = redisStockRepository.decrement(concertId);
    if (remaining < 0) {
        redisStockRepository.increment(concertId);  // 복구
        throw new SoldOutException(concertId);
    }
    ticketProducer.publishReservationRequest(concertId, userId);  // DB 저장 비동기 위임
    return new ReserveResponse(null, ReservationStatus.SUCCESS);  // 즉시 SUCCESS 반환
}

// TicketConsumer.java — DB 저장만 담당 (재고 판단 없음)
@KafkaListener(topics = "${ticketing.kafka.topic}", concurrency = "1")
public void consumeReservationRequest(String payload) {
    ReservationMessage msg = deserialize(payload);
    concert.decrease();
    reservationRepository.save(Reservation.of(msg.concertId(), msg.userId(), SUCCESS));
}
```

**특징**: Redis DECR 원자성으로 재고를 즉시 선점 → SUCCESS/FAIL 즉시 반환. DB 저장은 성공자에 한해 Kafka Consumer가 비동기 처리. `reservationId`는 응답에 포함되지 않음(비동기 저장).

---

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

> V4 TPS가 낮은 이유: `Thread.sleep(100ms)` 스핀 대기가 쓰레드를 점유 → 동시 처리 수 제한

### 시나리오 B — 실제 티켓팅 흐름 (재고 100,000장, 동시 2,000명)

전체 플로우: 공연 목록 → 상세 조회 → 예약 → 결제 (think time 포함)

| 버전 | 방식 | P99 | req/s | 에러율 |
|------|------|-----|-------|--------|
| V1 | No Lock | 5,039ms | 262 | 5.1% |
| V2 | Pessimistic | 7,194ms | 222 | 0% |
| V3 | Optimistic | 7,359ms | 216 | 0% |
| V4 | Spin Lock | **13,731ms** | 236 | 0% |
| V5 | Redisson | 8,538ms | 241 | 0% |
| V6 | Kafka | **2ms** | 400 | 0% |

> V4 P99 13.7초: 2,000명의 스핀 대기가 Redis를 지속 폴링 → 꼬리 레이턴시 폭발  
> V6: 예약 자체는 즉시 PENDING 반환 → P99 2ms, 실제 처리는 비동기

---

## 대시보드

`http://localhost:8080/dashboard` — Thymeleaf + Chart.js 실시간 성능 비교 대시보드

### 전체 뷰 (기본 — 시나리오 A, 1,000명)

![대시보드 전체 뷰](docs/images/dashboard-full.png)

### 시나리오 A 필터 (극한 경합, 1,000명)

![시나리오 A 1000명 필터](docs/images/dashboard-scenario-a-1000.png)

V4 스핀락의 TPS 급감(250)과 P99 폭발(2,966ms)이 차트에서 명확히 드러납니다.

### 시나리오 B 필터 (실제 티켓팅 흐름, 2,000명)

![시나리오 B 2000명 필터](docs/images/dashboard-scenario-b-2000.png)

V4 P99 13,731ms vs V6 P99 2ms — Kafka 비동기 방식의 압도적 레이턴시 차이.

**제공 기능**:
- 시나리오(A/B) · 버전(V1~V6) · 동시 사용자 수 필터
- TPS / P99 / 오버부킹 / 에러율 막대 차트
- 전체 결과 테이블 36건 (삭제 가능)

---

## 실행 방법

### 사전 요구사항

- Java 17+
- MySQL 8.x (localhost:3306, DB: `ticketing`)
- Redis 7.x (localhost:6379)
- Docker (Kafka용)

### 1. Kafka 기동

```bash
docker-compose -f kafka-docker-compose.yml up -d
```

### 2. DB 초기화

```sql
CREATE DATABASE IF NOT EXISTS ticketing CHARACTER SET utf8mb4;
```

### 3. 애플리케이션 실행

```bash
./gradlew bootRun
```

> `application.yml`의 MySQL 비밀번호를 환경에 맞게 수정하세요.

### 4. 초기 데이터 삽입

```bash
# 공연 생성 (앱 실행 후)
mysql -u root -p ticketing -e "
  INSERT INTO concert (title, total_stock, stock, version)
  VALUES ('테스트 콘서트', 100000, 100000, 0);
"
```

### 5. 부하 테스트 실행

```bash
cd load-test/gatling

# 시나리오 A — 극한 경합 (재고 100장으로 reset 필요)
curl -X POST "http://localhost:8080/api/concerts/1/reset?stock=100"
mvn gatling:test -Dgatling.simulationClass=ScenarioASimulation -DVERSION=v5 -DUSERS=1000

# 시나리오 B — 처리량 측정 (재고 100,000장으로 reset 필요)
curl -X POST "http://localhost:8080/api/concerts/1/reset?stock=100000"
mvn gatling:test -Dgatling.simulationClass=ScenarioBSimulation -DVERSION=v5 -DUSERS=1000
```

### 6. 대시보드 확인

`http://localhost:8080/dashboard`에서 결과를 확인합니다.

---

## 핵심 트레이드오프

```
정합성:    V2 = V3 = V4 = V5 = V6 >> V1
TPS:       V6 > V5 ≈ V2 ≈ V3 > V4 > V1
P99:       V6 << V5 < V2 ≈ V3 << V4
에러율:    V2=V3=V5=V6=0% ≤ V4 < V1
운영복잡:  V1 < V2 < V3 < V4 < V5 < V6
실시간응답: V1~V6 모두 가능 (V6는 Redis DECR로 즉시 SUCCESS/FAIL 반환)
```

### 도메인별 권장 전략

| 도메인 | 권장 | 이유 |
|--------|------|------|
| 일반 티켓팅 (실시간 응답) | **V5 Redisson** | 정합성 + 분산 환경 + P99 균형 |
| 극한 트래픽 + 즉시 응답 보장 | **V6 Redis선점+Kafka** | Redis DECR 즉시 응답 + DB 부하 최소화 |
| 충돌이 드문 일반 쇼핑몰 | **V3 Optimistic** | 읽기 성능 우수, 락 오버헤드 없음 |
| 단일 서버 환경 | **V2 Pessimistic** | 구현 단순, 강력한 정합성 |

---

## 면접 예상 질문

**Q. Redis 분산 락에서 Lettuce 대신 Redisson을 선택한 이유는?**

Spin Lock(V4)은 락 획득 실패 시 100ms마다 Redis에 폴링 요청을 보내 Redis 부하가 증가합니다. 반면 Redisson(V5)은 락 해제 시 Pub-Sub 이벤트로 대기 쓰레드에 통보하므로 불필요한 폴링이 없습니다. 실측 결과 V4 대비 Redis 명령어는 3.3배 많지만 TPS는 3배 높고 P99는 81% 감소했습니다. 이 역설의 이유는 스핀 대기가 쓰레드를 점유하는 반면, Pub-Sub 방식은 이벤트를 기다리며 쓰레드를 해방하기 때문입니다.

**Q. 낙관적 락을 티켓팅에 쓰지 않은 이유는?**

낙관적 락은 충돌이 드물 때 유리합니다. 하지만 티켓팅처럼 수천 명이 동일 Concert 레코드를 동시에 수정하는 환경에서는 충돌률이 거의 100%에 달합니다. 충돌 시 재시도 → 재시도도 충돌 → 재시도 폭발이 발생합니다. 실측 결과 2,000명 기준 V3 P99가 7,359ms로 비관적 락(7,194ms)보다 오히려 높게 나왔습니다.

**Q. Kafka 방식의 단점과 해결 방법은?**

단일 컨슈머 순차 처리로 동시성을 회피하므로 즉각 예약 성공/실패를 알릴 수 없습니다. 실무에서는 "대기열 진입" 상태를 즉시 반환하고 SSE 또는 폴링으로 결과를 안내하는 UX 설계가 필요합니다. 처리량 확장이 필요하면 파티션 수를 늘리고 concert ID를 파티션 키로 사용하면 같은 공연의 예약은 동일 파티션에서 순차 처리되어 정합성을 유지할 수 있습니다.

**Q. @Transactional과 분산 락의 순서가 왜 중요한가?**

트랜잭션 종료(커밋) 전에 락을 해제하면, 다른 스레드가 락을 획득하고 DB를 조회했을 때 이전 트랜잭션의 변경사항이 아직 커밋되지 않아 정합성이 깨질 수 있습니다. 따라서 V4/V5에서 `@Transactional`은 내부 메서드에만 적용하고, 락 획득/해제는 바깥 메서드에서 처리했습니다. `finally` 블록으로 항상 락 해제를 보장합니다.

**Q. 비관적 락에서 데드락을 어떻게 방지했는가?**

두 가지 방법을 적용했습니다. 첫째, `@Transactional(timeout=3)`으로 락 대기 시간을 제한해 교착 상태 발생 시 자동으로 롤백됩니다. 둘째, 여러 공연을 동시에 잠궈야 하는 경우 항상 동일한 순서(ID 오름차순)로 락을 획득해 사이클이 발생하지 않도록 했습니다.

---

## 지식 정리 문서

이 프로젝트에서 다룬 모든 개념을 **면접 완벽 답변 기준**으로 정리한 문서입니다.  
쉬운 개념부터 어렵고 중요한 것까지 11단계로 구성되어 있습니다.

| 레벨 | 제목 | 핵심 내용 |
|------|------|---------|
| [Level 1](docs/knowledge/level-01-concurrency-basics.md) | 동시성 문제의 본질 | Race Condition, Lost Update, 티켓팅이 교과서적 사례인 이유 |
| [Level 2](docs/knowledge/level-02-transaction-isolation.md) | 트랜잭션과 격리 수준 | ACID, 4단계 격리 수준, MVCC, Undo Log, Read View |
| [Level 3](docs/knowledge/level-03-pessimistic-lock.md) | DB 비관적 락 | InnoDB 락 타입, SELECT FOR UPDATE, 데드락 Coffman 조건, 방지 전략 |
| [Level 4](docs/knowledge/level-04-optimistic-lock.md) | DB 낙관적 락 | @Version 원리, flush 시점 예외, Retry Storm, 티켓팅 부적합 증명 |
| [Level 5](docs/knowledge/level-05-lettuce-spin-lock.md) | Redis Lettuce Spin Lock | SETNX+EXPIRE 함정, SET NX EX, UUID+Lua Script 주인 확인, P99 폭발 원인 |
| [Level 6](docs/knowledge/level-06-redisson-pubsub-lock.md) | Redisson Pub-Sub Lock | Redis Hash 구조, 획득/해제 Lua Script, Watchdog, 스레드 역설 분석 |
| [Level 7](docs/knowledge/level-07-transactional-deep-dive.md) | @Transactional 심화 | CGLIB 프록시, Self-Invocation 해결, 커밋 후 락 해제, 1차 캐시 충돌 |
| [Level 8](docs/knowledge/level-08-kafka-async-queue.md) | Kafka 비동기 대기열 | 파티션/오프셋, 단일 파티션 순차 처리, acks/idempotent, SSE vs 폴링 |
| [Level 9](docs/knowledge/level-09-spring-boot-4x.md) | Spring Boot 4.x 특이사항 | Jackson tools.jackson 패키지 변경, Kafka 자동구성 제거, @EnableKafka |
| [Level 10](docs/knowledge/level-10-gatling-load-test.md) | Gatling 부하 테스트 설계 | DSL 핵심, atOnceUsers vs rampUsers, P99 해석, V6 측정 함정 |
| [Level 11](docs/knowledge/level-11-interview-qa.md) | 면접 Q&A 전체 정리 | 16개 질문 완벽 답변 스크립트 + 현장 치트시트 |

---

## 프로젝트 구조

```
ticketing-system/
├── src/main/java/com/example/ticketing/
│   ├── concert/             # 공연 엔티티, 재고 관리
│   ├── reservation/
│   │   ├── controller/      # ReserveControllerV1 ~ V6
│   │   ├── service/         # TicketServiceV1 ~ V6
│   │   └── kafka/           # TicketProducer, TicketConsumer
│   ├── payment/             # 결제 Mock (100~200ms 지연)
│   ├── dashboard/           # TestResult 엔티티 + Chart.js 대시보드
│   └── global/
│       ├── config/          # RedissonConfig, KafkaConfig
│       └── exception/       # GlobalExceptionHandler
├── load-test/gatling/
│   ├── ScenarioASimulation.scala   # 극한 경합 시나리오
│   ├── ScenarioBSimulation.scala   # 실제 티켓팅 흐름 시나리오
│   └── common/Feeders.scala
├── docs/
│   ├── performance-report.md       # 전체 성능 비교표 (시나리오A/B × 500/1000/2000명)
│   └── knowledge/                  # 면접 완벽 답변 기준 지식 정리 (Level 1~11)
└── kafka-docker-compose.yml
```
