# 안태호 포트폴리오

---

## 티켓 예매 서비스

### 프로젝트 소개

대용량 트래픽 환경에서 발생하는 동시성 이슈를 6가지 락 전략으로 해결하고,  
각 방식의 성능·트레이드오프를 Gatling 실측 데이터로 비교한 백엔드 포트폴리오

| 프로젝트 기간 | 프로젝트 주소 | 참여 인원 |
|---|---|---|
| 2026.2 ~ 2026.3 | https://github.com/AnTaeho/ticketing-system | 개인 프로젝트 |

---

### 사용 기술

- Java 17, Spring Boot 4.0.6, Spring Data JPA / Hibernate 7.x
- MySQL 8.x, Redis 7.x (Lettuce, Redisson 3.50.0)
- Apache Kafka 3.x, Resilience4j (Circuit Breaker)
- Gatling 3.9.5 (부하 테스트), Thymeleaf + Chart.js (결과 대시보드)

---

### 1. 동시성 제어 — 6가지 락 전략 비교

#### 문제 상황

- 100장 티켓에 수천 명이 동시에 예약을 시도하면 **중복 예약(오버부킹)** 발생
- 단순 `SELECT → 재고 확인 → UPDATE` 흐름에서 여러 트랜잭션이 동시에 AVAILABLE 상태를 읽어 race condition 발생
- "어떤 락을 쓰면 정합성이 보장되는가"뿐 아니라 **"각 방식이 성능에 어떤 영향을 미치는가"** 를 실측으로 비교

#### 해결 방법

**V2: DB 비관적 락 (SELECT … FOR UPDATE)**

- `@Lock(LockModeType.PESSIMISTIC_WRITE)` 으로 조회 시 행 잠금
- 한 트랜잭션이 잠금을 보유하는 동안 다른 트랜잭션은 대기
- 트랜잭션 종료 후 다음 트랜잭션이 이미 예약된 상태를 확인하여 예외 처리

**V3: DB 낙관적 락 (@Version + 재시도)**

- `@Version` 컬럼으로 `UPDATE WHERE version = ?` 충돌 감지
- `ObjectOptimisticLockingFailureException` 발생 시 최대 10회 재시도
- 충돌이 적은 상황에서는 유리하나 **티켓팅처럼 충돌이 많은 환경에서는 재시도 폭발로 역전**

**V4: Redis Lettuce Spin Lock**

- `SET key value NX EX` 단일 명령으로 원자적 락 획득
- 락 획득 실패 시 100ms 간격으로 재시도 (Spin Wait)
- 트랜잭션 분리 핵심: 락 획득/해제를 `@Transactional` 외부에서 처리 → **트랜잭션 커밋 후 락 해제** 보장
- 스핀 대기는 락 해제 시까지 Redis에 지속 폴링 → 대기열이 길수록 Redis 부하 증가

**V5: Redis Redisson Pub-Sub Lock**

- Redisson `RLock`의 `tryLock(waitTime=5s, leaseTime=3s)` 사용
- 락 해제 시 Pub-Sub으로 대기 중인 스레드에 이벤트 발행 → **불필요한 폴링 제거**
- Watchdog으로 트랜잭션이 길어질 때 락 만료 자동 방지
- V4 대비 Redis 명령어 수는 3.3× 많지만, 폴링 대기 제거로 실질 TPS 3× 향상

**V6: Redis 선점 + Kafka 비동기 DB**

- Redis `DECR` 명령으로 재고를 원자적으로 선점 → 즉시 SUCCESS/FAIL 응답
- 성공한 요청만 Kafka 토픽으로 발행 → 단일 컨슈머가 순차적으로 DB 처리
- DB 트랜잭션을 성공자에 한해서만 실행 → DB 부하 최소화
- 단점: 수천 명이 동시에 DECR를 시도 → Redis에 순간 스파이크, 공정한 순서 보장 없음

**V7: Redis Sorted Set 대기열 + DECR 재고 선점**

- V6의 문제 해결: 모든 요청이 동시에 Redis에 몰리는 대신, **대기열로 유입량 자체를 제어**
- 예약 요청은 **2단계**로 분리: 대기열 토큰 발급 → 토큰 보유자만 예약 시도 가능

```
사용자 → 토큰 발급 요청
  └─ 처리열(≤200명) 공간 있음? → PROCESSING 토큰 즉시 발급
  └─ 공간 없음? → WAITING 토큰 발급, 폴링으로 상태 확인
         ↑
  스케줄러 3초마다: 만료 토큰 제거 → 대기→처리 자동 승격
         ↓
PROCESSING 토큰 보유자만 예약 API 호출
  └─ Redis DECR 재고 선점 → @Async DB 저장 → 즉시 SUCCESS/FAIL 응답
                                      └─ DB 실패 시 Redis increment 보상
```

- **Sorted Set score = `currentTimeMs + TTL`**: score가 낮을수록 먼저 입장한 토큰 → FIFO 순서 보장 + score 자체가 만료 시각 → 단일 자료구조로 순서·만료를 동시 처리
- `PROCESSING_QUEUE_SIZE = 200`으로 동시 DB 부하를 운영자가 제어 가능
- 토큰 TTL: 처리열 30분 / 대기열 60분 → 노쇼 방지 및 자동 자리 회수

#### 결과

**시나리오 A — 극한 경합 (재고 100장, 동시 사용자별 오버부킹 검증)**

| 버전 | 방식 | 동시 500명 P99 | 동시 1,000명 P99 | 동시 2,000명 P99 | 오버부킹 |
|------|------|--------------|----------------|----------------|---------|
| V1 | No Lock | 349ms | 407ms | 569ms | **발생** (lost update) |
| V2 | DB 비관적 락 | 304ms | 338ms | 668ms | 0건 ✅ |
| V3 | DB 낙관적 락 | 275ms | 338ms | 473ms | 0건 ✅ |
| V4 | Lettuce Spin | 2,532ms | 2,966ms | 2,598ms | 0건 ✅ |
| V5 | Redisson Pub-Sub | 469ms | 731ms | 1,395ms | 0건 ✅ |
| V6 | Redis 선점 + Kafka | **116ms** | **155ms** | **277ms** | 0건 ✅ |

**시나리오 B — 실제 티켓팅 흐름 (공연 조회 → 예약 → 결제, 동시 2,000명)**

| 버전 | 방식 | P99 | req/s | 에러율 |
|------|------|-----|-------|--------|
| V2 | DB 비관적 락 | 7,194ms | 222.2 | 0% |
| V4 | Lettuce Spin | **13,731ms** | 236.2 | 0% |
| V5 | Redisson Pub-Sub | 8,538ms | 241.4 | 0% |
| V6 | Redis 선점 + Kafka | **2ms** | 400.0 | 0% |

- **V4 꼬리 레이턴시 폭발**: 스핀 대기 스레드가 락 해제까지 Redis를 지속 폴링 → 대기열이 길수록 P99 급증 (2,000명에서 13.7초)
- **V4 vs V5 역설**: Redis 명령어 수는 V5가 3.3× 많지만, TPS는 V5가 3× 높음 → Pub-Sub 이벤트 기반 대기가 스레드를 해방
- **V6 즉시 응답**: Redis DECR 원자 연산으로 재고 선점 → DB 처리 없이 즉시 응답 → P99 2ms 달성

---

### 2. Circuit Breaker + Graceful Degradation (V5CB)

#### 문제 상황

- 실무에서 Redis 장애 발생 시 V5 전체 서비스가 중단되는 단일 장애점 문제
- Redis가 응답 불능 상태일 때 모든 요청이 `LockAcquisitionFailedException`으로 즉시 실패
- **장애 상황에서도 서비스를 지속**할 수 있는 Graceful Degradation 설계 필요

#### 해결 방법

**Resilience4j Circuit Breaker 적용 — Redis → DB 비관적 락 자동 폴백**

```
정상: V5 (Redisson) → Redis 락 → DB 저장
장애: Circuit Breaker OPEN → V2 (DB 비관적 락) 자동 폴백
```

- `failureRateThreshold=50%`, `minimumNumberOfCalls=10` — 최소 호출 후 실패율로 OPEN 판단
- OPEN 상태에서 `waitDurationInOpenState=10s` 후 HALF_OPEN 전환, 자동 회복 검증
- `ChaosAspect`로 Redis 장애를 코드 레벨에서 주입하여 전체 상태 전환 실측 확인
- `CircuitBreakerStatsHolder`로 `fallbackCount`, `cbTripCount` 실시간 관찰

#### 결과 (Gatling, 시나리오 A, 동시 500명)

| 시나리오 | CB 상태 | P99 | TPS | 에러율 | fallbackRatio |
|---------|---------|-----|-----|--------|---------------|
| 정상 (Redis 정상) | CLOSED | 1,150ms | 661 | 0% | 0% |
| 장애 (Redis 차단) | OPEN → V2 폴백 | 411ms | 1,946 | 0% | 100% |

- Redis 장애 시 에러율 0% 유지 — 서비스 무중단 폴백 검증
- 폴백(OPEN) 상태가 정상(CLOSED)보다 빠른 이유: ChaosAspect가 Redis 연산을 즉시 차단 → Redisson 락 경합 오버헤드(500명 동시 대기) 제거 → V2 DB 락이 더 단순한 직렬화 처리

---

### 3. Gatling 부하 테스트 + 결과 대시보드

#### 문제 상황

- 각 락 전략의 성능을 **동일한 조건에서 정량적으로 비교**할 수 있는 환경 필요
- 테스트 결과를 매번 텍스트로 기록하면 비교·분석이 어려움

#### 해결 방법

**2가지 시나리오로 부하 테스트 설계**

- **시나리오 A (극한 경합)**: `atOnceUsers(N)` — 재고 100장에 동시 500/1,000/2,000명 즉시 투입, 정합성 검증
- **시나리오 B (실제 흐름)**: `rampUsers(N)` — 공연 조회 → 예약 → 결제 4단계 플로우, think time 포함, 실질 처리량 측정

**테스트 결과 DB 저장 + 대시보드 시각화**

- 각 테스트 완료 후 `POST /api/test-results`로 TPS, P99, 에러율, 오버부킹 건수 저장
- Thymeleaf + Chart.js 대시보드로 버전별 성능 비교 차트 렌더링
  - 오버부킹 건수 막대 차트 (정합성 비교)
  - TPS / P99 비교 차트 (처리량 비교)
  - Circuit Breaker fallback 비율 차트

#### 결과

- V1~V6 + V5CB 전 버전 시나리오 A/B 완료, **총 36건 이상 측정 결과 DB 저장**
- 동일 조건 반복 실행으로 측정 신뢰도 확보
- 대시보드에서 버전별 트레이드오프를 한눈에 시각화

---

### 핵심 트레이드오프 요약

```
정합성:      V2 ~ V7 모두 0건 (V1만 lost update 발생)
TPS:         V7 > V6 > V5 > V3 ≈ V2 > V4 (시나리오 A 기준)
즉시 응답:   V1~V5, V7 가능 / V6 비동기 (PENDING → 폴링)
P99 안정성:  V7 > V6 > V5 > V2 ≈ V3 > V4 (꼬리 레이턴시)
서버 보호:   V7 (대기열) > V6 > V5 > V4 > V3 > V2 > V1
운영 복잡도: V1 < V2 < V3 < V4 < V5 < V5CB < V6 < V7
```

**결론**:
- **일반 티켓팅**: V5 (Redisson) — 즉시 응답 + 적정 성능의 균형
- **폭발적 트래픽**: V7 (Redis 대기열) — 유입량 제어 + FIFO 공정성 + DB 부하 최소화
- **운영 안전망**: V5CB (Circuit Breaker) — Redis 장애 시 V2 자동 폴백으로 서비스 무중단

---

### 4. 코드 함정 분석 & 개선

> 프레젠테이션 자료 리뷰 과정에서 각 버전 코드에 잠재된 함정 8개를 발견하고 직접 수정.  
> 단순히 "동작하는 코드"가 아닌 **엣지 케이스까지 고려한 코드**로 개선한 기록.

---

#### 함정 1 — @Version 측정 왜곡 (전체 버전)

**문제**  
`Concert` 엔티티에 `@Version`이 선언되어 있어, V1(No Lock)도 JPA 낙관적 락의 안전망을 암묵적으로 가진다.  
두 스레드가 동시에 같은 version 값으로 UPDATE를 시도하면 한 쪽이 `OptimisticLockingFailureException`으로 롤백되기 때문에 V1의 오버부킹 수치가 실제 "무방비" 상태보다 낮게 나온다.

**원인**  
```java
// Concert.java
@Version
private Long version;  // V1 ~ V7 모두 동일 엔티티 사용
```

**수정 방향**  
별도 엔티티 분리는 오버엔지니어링이므로 측정 왜곡 사실을 문서화하고, V4/V5에서 분산 락이 깨졌을 때 `@Version`이 최후 방어선으로 작동하는 **의도된 다층 방어**로 재정의.

---

#### 함정 2 — Thundering Herd (V3 낙관적 락)

**문제**  
충돌 후 재시도 대기 시간이 고정 50ms여서, 동시에 충돌한 N개의 스레드가 정확히 50ms 뒤 다시 동시에 재충돌한다.  
티켓팅처럼 충돌이 거의 100%인 환경에서 재시도 폭풍(Thundering Herd)이 발생해 DB 부하가 V2보다 커질 수 있다.

**원인**  
```java
// Before — OptimisticLockRetryer.java
private void sleep() {
    Thread.sleep(50);  // 고정값: 동시 충돌 → 동시 재시도 반복
}
```

**수정**  
지수 백오프 + 지터(Jitter) 적용. `2^retry`로 백오프를 늘리고 random 값을 더해 재충돌 타이밍을 분산시킨다.

```java
// After — 지수 백오프 + 지터
private static final long BASE_WAIT_MS = 50;
private static final long MAX_WAIT_MS  = 1_000;

private void sleepWithBackoff(int retryCount) {
    long backoffMs  = BASE_WAIT_MS * (1L << retryCount); // 50 → 100 → 200 → …
    long cappedMs   = Math.min(backoffMs, MAX_WAIT_MS);
    long jitteredMs = (long) (Math.random() * cappedMs); // 0 ~ cappedMs 사이 랜덤
    Thread.sleep(jitteredMs);
}
```

---

#### 함정 3 — 비소유자 락 삭제 (V4 Lettuce Spin Lock) ★ 핵심

**문제**  
`releaseLock()`이 소유자 확인 없이 무조건 `DELETE`를 수행해 **남의 락을 지우는** 상황이 발생한다.

```
t=0  A: 락 획득 (TTL 3초)
t=3  TTL 만료 → A의 락 자동 소멸
t=3  B: 락 획득
t=4  A: finally 블록 실행 → DELETE → B의 락 삭제됨
t=4  C: 락 획득 → B·C 동시 임계 구역 진입 → 정합성 파괴
```

**원인**  
```java
// Before — LettuceLockRepository.java
public void releaseLock(Long concertId) {
    redisTemplate.delete(buildKey(concertId)); // 소유자 검증 없음
}
```

**수정**  
`tryLock`에서 UUID를 값으로 저장하고, `releaseLock`에서 **GET → 일치할 때만 DEL** 을 Lua 스크립트로 원자 실행한다.  
GET과 DEL을 분리하면 그 사이에 또 다른 레이스가 생기므로 반드시 Lua로 묶어야 한다.

```java
// After — LettuceLockRepository.java
private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
    "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
    Long.class
);

public String tryLock(Long concertId) {
    String lockValue = UUID.randomUUID().toString();
    Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(buildKey(concertId), lockValue, LOCK_TTL);
    return Boolean.TRUE.equals(acquired) ? lockValue : null; // 획득 성공 시 UUID 반환
}

public void releaseLock(Long concertId, String lockValue) {
    redisTemplate.execute(RELEASE_SCRIPT,
            Collections.singletonList(buildKey(concertId)), lockValue);
}
```

`TicketServiceV4`도 UUID를 로컬 변수로 관리하도록 수정:

```java
// After — TicketServiceV4.java
String lockValue = acquireSpinLock(concertId); // UUID 반환
try {
    return transaction.reserveInTransaction(concertId, userId);
} finally {
    lettuceLockRepository.releaseLock(concertId, lockValue); // 소유자 UUID 전달
}
```

---

#### 함정 4 — 워치독 비활성화 (V5 Redisson)

**문제**  
Redisson의 워치독(Watchdog)은 `leaseTime`을 **지정하지 않을 때만** 동작한다.  
`leaseTime=3s`를 명시하면 워치독이 꺼져 TTL이 3초 고정되고, 트랜잭션이 3초를 초과하면 V4와 동일한 TTL 만료 문제가 재현된다.

**원인**  
```java
// Before — TicketServiceV5.java
private static final long LOCK_LEASE_SECONDS = 3;

lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
//                               ↑ leaseTime 명시 → 워치독 꺼짐
```

**수정**  
`leaseTime` 인자를 제거해 Redisson 기본 워치독(lease 30초, 10초마다 자동 갱신)을 활성화.

```java
// After — TicketServiceV5.java
lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS); // leaseTime 제거 → 워치독 ON
```

> **트레이드오프**: 워치독 사용 시 앱이 갑자기 죽으면 락이 최대 30초간 잠길 수 있다.  
> 운영에서는 leaseTime을 충분히 크게 명시하거나, 워치독 + 알람 체계를 함께 구성한다.

---

#### 함정 5 — RedisConnectionException 집계 누락 (V5CB Circuit Breaker)

**문제**  
CB가 실패로 집계하는 예외를 `RedisCommandTimeoutException`(타임아웃)만으로 한정했다.  
Redis 서버가 완전히 다운되면 `RedisConnectionException`(연결 거부)이 발생하는데 이는 집계에서 제외되어 CB가 OPEN되지 않는다.  
결과적으로 모든 요청이 타임아웃을 기다린 뒤에야 폴백 — 최악의 레이턴시.

추가로, `e.getCause()` 1단계만 확인해 깊게 중첩된 cause 체인에 있는 Redis 예외도 놓쳤다.

**원인**  
```java
// Before — ResilienceConfig.java
.recordException(e ->
    e instanceof RedisCommandTimeoutException ||
    (e.getCause() != null && e.getCause() instanceof RedisCommandTimeoutException))
// RedisConnectionException 없음, cause 체인 1단계만 탐색
```

**수정**  
`RedisConnectionException` 추가 + while 루프로 cause 체인 끝까지 순회.

```java
// After — ResilienceConfig.java
.recordException(this::isRedisInfraFailure)

private boolean isRedisInfraFailure(Throwable e) {
    Throwable cause = e;
    while (cause != null) {
        if (cause instanceof RedisCommandTimeoutException ||
            cause instanceof RedisConnectionException) {
            return true;
        }
        cause = cause.getCause(); // 체인 끝까지 탐색
    }
    return false;
}
```

---

#### 함정 6 — catch(Throwable)로 코드 버그 은폐 (V5CB)

**문제**  
`catch(Throwable)`이 NPE, `StackOverflowError` 같은 코드 버그까지 잡아 V2 폴백으로 조용히 처리한다.  
Redis 인프라 장애가 아닌 코드 버그가 발생해도 폴백이 성공하면 **에러가 없는 것처럼 보여** 디버깅이 극도로 어려워진다.

**원인**  
```java
// Before — TicketServiceV5CB.java
} catch (Throwable e) {
    if (e instanceof ConcertNotFoundException cnfe) throw cnfe;
    ...
    return ticketServiceV2.reserve(...); // NPE도 여기로 빠짐
}
```

**수정**  
비즈니스 예외를 먼저 `catch`로 분리하고, 나머지 중 Redis 인프라 예외만 폴백. 그 외 미지 예외(`RuntimeException` 서브타입 등)는 재던져 스택 트레이스가 남도록 한다.

```java
// After — TicketServiceV5CB.java
} catch (ConcertNotFoundException | LockAcquisitionFailedException | SoldOutException e) {
    throw e; // 비즈니스 예외: 그대로 위로
} catch (Throwable e) {
    if (!isRedisInfraFailure(e)) {
        throw e instanceof RuntimeException re ? re : new RuntimeException(e); // 코드 버그: 재던짐
    }
    // Redis 인프라 장애만 폴백
    return ticketServiceV2.reserve(concertId, userId);
}
```

---

#### 함정 7 — ack 순서 역전으로 메시지 유실 (V6 Kafka Consumer)

**문제**  
`@Transactional` 메서드 안에서 `ack.acknowledge()`를 호출하면, `@Transactional` 커밋은 **메서드가 리턴한 후**에 발생하므로 오프셋 커밋이 DB 커밋보다 먼저 일어난다.

```
saveReservation() 성공 → ack.acknowledge() → 오프셋 커밋 → 메서드 리턴 → @Transactional 커밋 실패
→ DB 롤백, 오프셋은 이미 넘어감 → 메시지 영구 유실
```

**원인**  
```java
// Before — TicketConsumer.java
@Transactional
public void consumeReservationRequest(String payload, Acknowledgment ack) {
    saveReservation(...);
    ack.acknowledge(); // @Transactional 커밋 전 오프셋 커밋
}
```

**수정**  
`TransactionSynchronization.afterCommit()` 콜백에 ack를 등록한다.  
DB 커밋이 성공한 직후에만 오프셋 커밋 → DB 실패 시 메시지 재처리 가능.  
재처리 시 중복을 `existsByTicketToken()` 멱등성 체크로 방어하므로 안전하다.

```java
// After — TicketConsumer.java
saveReservation(message.concertId(), message.userId(), message.ticketToken());
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        ack.acknowledge(); // DB 커밋 성공 후에만 오프셋 커밋
    }
});
```

---

#### 함정 8 — ticketToken 미반환으로 폴링 불가 (V6)

**문제**  
PENDING 응답에 `ticketToken`이 포함되지 않아 클라이언트가 예약 결과를 조회할 수 없다.  
V6의 핵심 설계인 "PENDING 즉시 응답 → 폴링으로 결과 확인" 흐름이 완성되지 않은 상태.

**원인**  
```java
// Before — TicketServiceV6.java
return new ReserveResponse(null, ReservationStatus.PENDING);
// ticketToken이 응답에 없음 → 클라이언트가 폴링할 방법 없음
```

**수정**  
`ReserveResponse`에 `ticketToken` 필드를 추가하고, 기존 V1~V5 호환을 위해 2인자 컴팩트 생성자 유지.

```java
// After — ReserveResponse.java
public record ReserveResponse(Long reservationId, ReservationStatus status, String ticketToken) {
    public ReserveResponse(Long reservationId, ReservationStatus status) {
        this(reservationId, status, null); // V1~V5는 ticketToken=null
    }
}

// After — TicketServiceV6.java
return new ReserveResponse(null, ReservationStatus.PENDING, ticketToken);
// 클라이언트: GET /api/v6/reservations/{ticketToken} 으로 결과 폴링 가능
```

---

### 면접 예상 질문

**Q. Lettuce 대신 Redisson을 선택한 이유는?**  
→ Spin Lock은 락 획득 실패 시 Redis에 지속적으로 폴링 요청을 보내 부하가 증가합니다. Redisson의 Pub-Sub 방식은 락 해제 이벤트를 구독하여 불필요한 폴링을 제거합니다. 실측 결과 Redis 명령어 수는 V5가 3.3× 많지만, 폴링 대기 제거로 TPS는 3× 향상되었습니다.

**Q. 낙관적 락을 최종 선택하지 않은 이유는?**  
→ 낙관적 락은 충돌이 드물 때 유리하지만, 티켓팅처럼 동시 충돌이 많은 환경에서는 재시도가 폭발적으로 증가합니다. 시나리오 B 2,000명 기준 V3의 P99(7,359ms)는 V2(7,194ms)와 유사하거나 느려져 재시도 비용이 역전됨을 실측으로 확인했습니다.

**Q. @Transactional과 분산 락을 함께 쓸 때 주의할 점은?**  
→ 락 해제가 트랜잭션 커밋 이전에 발생하면 다른 스레드가 커밋 전 데이터를 읽어 정합성이 깨집니다. V4/V5에서는 락 획득·해제를 `@Transactional` 외부 메서드에서 처리하고, 트랜잭션을 별도 빈의 `@Transactional` 메서드로 분리하여 **트랜잭션 커밋 후 락 해제**를 보장했습니다.

**Q. Circuit Breaker의 폴백 상태가 정상보다 빠른 이유는?**  
→ `ChaosAspect`가 Redis 연산을 즉시 차단하기 때문에 Redisson 락 경합 오버헤드(500명 동시 대기)가 사라지고, V2 DB 비관적 락의 단순한 직렬화 처리로 대체됩니다. 락 경합 오버헤드가 DB 락 오버헤드보다 큰 상황에서는 폴백이 더 빠를 수 있다는 반직관적 결과였습니다.

**Q. V7 대기열에서 Sorted Set score를 만료 시각으로 설정한 이유는?**  
→ score = `currentTimeMs + TTL`로 설정하면 두 가지를 동시에 해결합니다. 첫째, score가 낮을수록 먼저 입장한 토큰이므로 `ZRANGE` 명령 하나로 FIFO 순서를 보장합니다. 둘째, `ZREMRANGEBYSCORE(-∞, now)`로 만료된 토큰을 별도 자료구조 없이 바로 제거할 수 있습니다. 순서 관리와 TTL 처리를 단일 Sorted Set으로 통합한 것이 핵심입니다.

**Q. V6(Kafka)와 V7(Redis 대기열)의 차이는?**  
→ V6은 재고 선점(DECR)에는 성공했지만, 수천 명이 동시에 DECR를 시도하는 순간 Redis에 스파이크가 발생하고 입장 순서도 보장되지 않습니다. V7은 대기열로 동시 처리 인원을 `PROCESSING_QUEUE_SIZE=200`으로 제한해 Redis·DB 부하를 운영자가 직접 통제하고, FIFO 순서로 공정성도 확보합니다. V6이 "빠른 처리량"에 최적화되어 있다면, V7은 "제어 가능한 처리량 + 공정성"에 최적화된 구조입니다.
