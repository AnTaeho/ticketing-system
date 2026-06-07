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

> 도메인 기반 패키지 구조 (concert / reservation / payment / dashboard)

```
ticketing-system/
├── src/main/java/
│   └── com.example.ticketing/
│       ├── TicketingApplication.java
│       ├── concert/
│       │   ├── controller/ConcertController.java
│       │   ├── domain/Concert.java
│       │   ├── repository/ConcertRepository.java
│       │   └── service/ConcertService.java
│       ├── reservation/
│       │   ├── controller/
│       │   │   ├── ReserveControllerV1.java ~ ReserveControllerV6.java
│       │   ├── domain/
│       │   │   ├── Reservation.java
│       │   │   └── ReservationStatus.java
│       │   ├── dto/
│       │   │   ├── ReserveRequest.java
│       │   │   ├── ReserveResponse.java
│       │   │   └── AsyncReserveResponse.java
│       │   ├── repository/ReservationRepository.java
│       │   └── service/
│       │       ├── TicketService.java         # 인터페이스
│       │       ├── TicketServiceV1.java       # No Lock
│       │       ├── TicketServiceV2.java       # Pessimistic Lock
│       │       ├── TicketServiceV3.java       # Optimistic Lock
│       │       ├── TicketServiceV4.java       # Redis Lettuce Spin Lock
│       │       ├── TicketServiceV5.java       # Redis Redisson Pub-Sub
│       │       └── TicketServiceV6.java       # Kafka 비동기 대기열
│       ├── payment/
│       │   ├── controller/PaymentController.java
│       │   ├── domain/Payment.java, PaymentStatus.java
│       │   ├── dto/PaymentRequest.java, PaymentResponse.java
│       │   ├── repository/PaymentRepository.java
│       │   └── service/PaymentService.java
│       ├── dashboard/                         # Phase 9
│       │   ├── controller/DashboardController.java
│       │   ├── domain/TestResult.java
│       │   ├── repository/TestResultRepository.java
│       │   └── service/DashboardService.java
│       └── global/
│           ├── config/
│           │   └── RedissonConfig.java
│           └── exception/
│               ├── GlobalExceptionHandler.java
│               ├── ConcertNotFoundException.java
│               ├── SoldOutException.java
│               ├── LockAcquisitionFailedException.java
│               └── ReservationFailedException.java
├── src/main/resources/
│   ├── templates/
│   │   └── dashboard/
│   │       └── index.html             # Thymeleaf 대시보드 뷰
│   └── static/
│       └── js/
│           └── dashboard.js           # Chart.js 차트 렌더링
├── load-test/
│   └── gatling/
│       ├── V1Simulation.scala
│       ├── V2Simulation.scala
│       ├── V3Simulation.scala
│       ├── V4Simulation.scala
│       ├── V5Simulation.scala
│       └── V6Simulation.scala
└── docs/
    ├── architecture.md
    ├── performance-report.md
    └── tradeoff-summary.md
```

---

## 버전별 구현 전략

### V1 — No Lock (기준선)

**목적**: 동시성 이슈를 의도적으로 발생시켜 기준 데이터 확보

**구현**:
- 순수 JPA `save()` 로직, 동기화 없음
- 재고 조회 → 감소 → 저장 사이에 Race Condition 발생

**예상 결과**:
- 오버부킹 다수 발생
- TPS는 가장 높음 (락 오버헤드 없음)

**기록 포인트**:
- 동시 요청 수 대비 오버부킹 건수
- "이 문제를 해결하기 위해 어떤 락을 적용했는가"의 출발점

---

### V2 — DB 비관적 락 (Pessimistic Lock)

**목적**: DB 레벨에서 충돌을 원천 차단

**구현**:
- `@Lock(LockModeType.PESSIMISTIC_WRITE)` 적용
- `SELECT ... FOR UPDATE` 쿼리 발생
- 트랜잭션 종료 시까지 다른 트랜잭션 대기

**예상 결과**:
- 정합성 100% 보장
- 동시 요청 증가 시 대기 큐 쌓여 TPS 급감
- 데드락 시나리오 테스트 및 기록

**기록 포인트**:
- TPS 감소 폭
- 데드락 발생 조건과 해결 방법 (`NOWAIT`, 타임아웃 설정)

---

### V3 — DB 낙관적 락 (Optimistic Lock)

**목적**: 충돌이 적을 때 유리한 방식 검증

**구현**:
- `@Version` 컬럼 추가
- 충돌 시 `ObjectOptimisticLockingFailureException` 발생 → 재시도 로직 구현

**예상 결과**:
- 충돌이 적은 상황: 성능 우수
- 충돌이 많은 상황(티켓팅): 재시도 폭발 → 성능 역전
- **티켓팅 도메인에서 낙관적 락이 왜 부적합한지** 실측으로 증명

**기록 포인트**:
- 동시 요청 수 증가에 따른 재시도 횟수 추이
- "어떤 상황에서 써야 하고 쓰면 안 되는지" 결론 도출

---

### V4 — Redis 분산 락 (Lettuce / Spin Lock)

**목적**: 애플리케이션 레벨 분산 락 도입

**구현**:
- `SETNX` + `EXPIRE` 직접 구현
- 락 획득 실패 시 일정 간격으로 재시도 (Spin Lock)
- 타임아웃 설정 미스 시나리오 테스트

**예상 결과**:
- 정합성 보장
- Spin Lock 특성상 락 획득 대기 중 Redis에 지속적인 요청 발생 → Redis 부하 증가
- 타임아웃 설정이 짧으면 정상 트랜잭션도 실패 가능

**기록 포인트**:
- Redis 커넥션 수 / CPU 사용률 모니터링
- 스핀 대기 횟수 측정

---

### V5 — Redis 분산 락 (Redisson / Pub-Sub)

**목적**: Spin Lock의 단점을 Pub-Sub 방식으로 개선

**구현**:
- `Redisson` 라이브러리의 `RLock` 사용
- 락 해제 시 Pub-Sub으로 대기 중인 스레드에 알림 → 불필요한 폴링 제거
- `tryLock(waitTime, leaseTime, TimeUnit)` 설정

**예상 결과**:
- V4 대비 Redis 부하 감소
- 처리량 유사하거나 개선
- 현재까지 가장 균형 잡힌 방식

**기록 포인트**:
- V4 vs V5 Redis 부하 비교 (Redis INFO stats)
- 실무에서 Redisson을 선택하는 이유 정리

---

### V6 — Kafka 비동기 대기열

**목적**: 락 자체를 없애고 순차 처리로 동시성 문제 회피

**구현**:
- 예약 요청을 Kafka 토픽으로 발행
- 단일 파티션 + 단일 컨슈머가 순서대로 처리
- 재고 변경은 컨슈머에서만 발생 → 동시성 이슈 원천 차단
- 사용자에게는 즉시 "대기 중" 응답 → SSE 또는 폴링으로 결과 전달

**예상 결과**:
- 처리량 극대화
- DB/Redis 락 오버헤드 없음
- 단점: 즉각 응답 불가 → UX 트레이드오프 존재

**기록 포인트**:
- 처리량(TPS) 극대화 수치
- "즉시 응답이 필요한 도메인에서는 어떻게 UX를 설계할 것인가" 고민 기록

**실무 관점 한계**:
- 사용자가 예매 버튼을 누른 시점에 성공/실패를 알 수 없음
- 단일 컨슈머가 병목 → 트래픽이 극단적으로 몰릴수록 대기 시간 증가
- 이를 해결하기 위해 V7(Redis 선점 + 비동기 DB + 대기열)로 진화

---

### V7 — Redis 선점 + 대기열 (실무 확장 설계)

**목적**: 즉시 응답 보장 + 극한 트래픽 완충 + DB 부하 최소화를 동시에 달성

**설계 배경**:
- V5는 즉시 응답이 가능하지만 10만 명이 몰리면 대부분 `LockAcquisitionFailedException`으로 즉시 실패
- V6은 처리량은 높지만 결과를 즉시 알 수 없는 UX 트레이드오프 존재
- 두 방식의 장점을 결합하여 **즉시 응답 + 서버 보호 + DB 부하 최소화** 달성

**전체 흐름**:

```
10만명 요청
    ↓
[1단계] 대기열 (트래픽 완충)
    - 서버로 오는 요청 자체를 제한
    - 앞 사람이 빠져나갈 때마다 순서대로 입장
    ↓
[2단계] Redis DECR 재고 선점 (원자적)
    - 100명 → 즉시 성공 응답 + ticketToken 발급
    - 나머지 → 즉시 실패 응답
    ↓
[3단계] 성공한 100명만 비동기 DB 처리 (Kafka or @Async)
    - DB 트랜잭션은 성공자에 한해서만 실행
    - 결제 페이지 진입 후 타임아웃(10분) 설정
    ↓
[4단계] 타임아웃 발생 시 재고 복구
    - Redis 재고 복구 → 대기열 다음 사람에게 자동 배분
    - 초기 폭발 이후 취소표는 V5(Redisson)로 전환
```

**구현**:
- 대기열: Redis Sorted Set (`ZADD`, `ZRANK`) — 진입 시각을 score로 순서 보장
- 재고 선점: `DECR` 명령 (원자적, 0 미만이면 복구 후 실패 처리)
- 비동기 DB: Kafka 토픽 발행 or `@Async` 메서드
- 타임아웃 관리: Redis TTL + 만료 이벤트(`notify-keyspace-events`) 또는 스케줄러

**운영 전략**:
- 일반 티켓팅: V5 (Redisson) — 무난한 트래픽에 적합
- 폭발적 티켓팅: V7 — 운영자가 사전에 수동 설정
- 최악의 경우 폴백: V5가 버텨주므로 운영자 판단 실수에도 서비스 다운 없음

**예상 결과**:
- 사용자: 즉시 성공/실패 응답 수신
- DB: 성공한 N명의 트랜잭션만 처리 (10만 건 → N건으로 감소)
- 서버: 대기열이 트래픽 완충 → Redis/DB 과부하 방지

**기록 포인트**:
- V5 vs V7 처리량 및 응답시간 비교
- 대기열 없을 때 vs 있을 때 Redis/DB 부하 비교
- 운영자 수동 설정 방식의 트레이드오프 (자동화 vs 안전성)

---

## 부하 테스트 시나리오

테스트는 목적이 다른 2가지 시나리오로 구성한다. 모든 버전(V1~V6)에 대해 두 시나리오를 모두 실행하여 결과를 비교한다.

---

### 시나리오 A — 극한 경합 (정합성 검증)

**목적**: 소량의 티켓에 대규모 인원이 동시에 몰리는 상황. 락 방식별로 **오버부킹이 발생하는지**, 얼마나 많이 발생하는지를 검증한다.

**설정**:

| 항목 | 값 |
|------|-----|
| 티켓 재고 | 100장 |
| 동시 사용자 수 | 500 / 1,000 / 2,000 단계별 |
| 테스트 시간 | 각 60초 |
| 요청 유형 | 단순 예약 단건 (`POST /reserve`) |

**핵심 측정 지표**:

| 지표 | 설명 |
|------|------|
| **오버부킹 건수** | 재고 초과 예약 수 — 정합성의 핵심 |
| **에러율** | 락 타임아웃 / 재시도 초과 비율 |
| **P99 응답시간** | 경합이 극심할 때 꼬리 레이턴시 |
| **TPS** | 참고용 (정합성이 우선) |

---

### 시나리오 B — 처리량 측정 (실제 티켓팅 흐름)

**목적**: 티켓이 충분한 상황에서 실제 사용자 행동 흐름을 시뮬레이션하여 락 방식별 **실질 처리량**을 측정한다. 단순 reserve 단건 호출은 실제 상황과 동떨어지기 때문에, 실제 티켓팅과 유사한 흐름(공연 조회 → 잔여석 확인 → 예약 → 결제)으로 구성한다.

**설정**:

| 항목 | 값 |
|------|-----|
| 티켓 재고 | 100,000장 (사실상 소진 없음) |
| 동시 사용자 수 | 500 / 1,000 / 2,000 단계별 |
| 테스트 시간 | 각 120초 |
| 요청 유형 | 아래 전체 플로우 (멀티 스텝) |

**사용자 행동 플로우**:

```
1. GET  /api/concerts                   → 공연 목록 조회        (think time: 1~2s)
2. GET  /api/concerts/{id}              → 공연 상세 / 잔여석 확인 (think time: 2~3s)
3. POST /api/v{n}/concerts/{id}/reserve → 예약 요청
4. POST /api/payments                   → 결제 처리 (Mock)
```

> think time: 실제 사용자가 화면을 보고 다음 행동까지 걸리는 지연 시간. 없으면 서버 부하가 비현실적으로 집중됨.

**추가 API (시나리오 B 전용)**:

```
GET  /api/concerts                     → 공연 목록 (페이징, 최대 20건)
GET  /api/concerts/{concertId}         → 공연 상세 + 잔여석 수
POST /api/payments                     → 결제 Mock
```

**결제 Mock 처리**: 외부 PG 연동 없이 `Reservation.status`를 `PAID`로 변경하는 것으로 대체. 단, 처리 시간을 현실적으로 맞추기 위해 `Thread.sleep(100~200ms)` 랜덤 지연을 추가한다.

**핵심 측정 지표**:

| 지표 | 설명 |
|------|------|
| **TPS** | 전체 플로우 기준 초당 완결 건수 |
| **P99 응답시간** | 각 스텝별 + 전체 플로우 기준 |
| **에러율** | 결제까지 완료 실패 비율 |
| **오버부킹 건수** | 재고 충분하므로 0이어야 정상 |

---

## 최종 성능 비교표 (작성 예시)

> 실제 측정 후 수치로 채울 것

### 시나리오 A — 극한 경합 (재고 100장, 동시 1,000명)

| 버전 | 방식 | 오버부킹 | TPS | P99 응답 | 에러율 | 비고 |
|------|------|----------|-----|----------|--------|------|
| V1 | No Lock | 다수 발생 | 최고 | 낮음 | 낮음 | 기준선 |
| V2 | Pessimistic Lock | 0건 | 낮음 | 높음 | 중간 | 데드락 위험 |
| V3 | Optimistic Lock | 0건 | 중간→낮음 | 중간 | 높음 | 충돌 많을수록 악화 |
| V4 | Lettuce Spin Lock | 0건 | 중간 | 중간 | 낮음 | Redis 부하 높음 |
| V5 | Redisson Pub-Sub | 0건 | 중상 | 중간 | 낮음 | **균형 최적** |
| V6 | Kafka Queue | 0건 | 최고 | N/A | 최저 | 비동기, UX 트레이드오프 |
| V7 | Redis 선점 + 대기열 | 0건 | 최고 | 낮음 | 최저 | 즉시 응답 + 서버 보호 |

### 시나리오 B — 처리량 측정 (재고 100,000장, 동시 1,000명, 전체 플로우)

| 버전 | 방식 | TPS (플로우) | P99 응답 | 에러율 | 오버부킹 | 비고 |
|------|------|-------------|----------|--------|----------|------|
| V1 | No Lock | - | - | - | - | |
| V2 | Pessimistic Lock | - | - | - | 0건 | |
| V3 | Optimistic Lock | - | - | - | 0건 | |
| V4 | Lettuce Spin Lock | - | - | - | 0건 | |
| V5 | Redisson Pub-Sub | - | - | - | 0건 | |
| V6 | Kafka Queue | - | - | - | 0건 | |
| V7 | Redis 선점 + 대기열 | - | - | - | 0건 | |

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
- **폭발적 트래픽 예상**: V7 (Redis 선점 + 대기열) — 운영자 사전 설정으로 전환
- **운영 안전망**: V7 실패 시 V5가 폴백으로 서비스 유지 보장

---

## 면접 예상 질문 & 답변 포인트

**Q. Redis 분산 락에서 Lettuce 대신 Redisson을 선택한 이유는?**
→ Spin Lock 방식은 락 획득 실패 시 Redis에 지속적으로 폴링 요청을 보내 부하가 증가함. Redisson의 Pub-Sub 방식은 락 해제 이벤트를 구독하여 불필요한 폴링을 제거, Redis 부하를 V4 대비 XX% 감소시킨 것을 실측으로 확인.

**Q. 낙관적 락을 쓰지 않은 이유는?**
→ 낙관적 락은 충돌이 드물 때 유리하나, 티켓팅처럼 동시 충돌이 많은 환경에서는 재시도가 폭발적으로 증가해 오히려 성능이 역전됨. V3 부하 테스트에서 동시 사용자 1,000명 기준 재시도 횟수가 XX회로 측정되어 부적합하다고 판단.

**Q. Kafka 방식의 단점은?**
→ 단일 컨슈머 순차 처리로 동시성을 회피하지만, 사용자에게 즉각 예약 성공/실패를 알릴 수 없음. 실무에서는 "대기열 진입" → SSE/폴링으로 결과 안내하는 UX 설계가 필요하며, 이 트레이드오프를 인지하고 선택해야 함.

**Q. 실무에서 극한 트래픽(아이유 콘서트 등)을 어떻게 설계하겠는가?**
→ V5(Redisson)를 기본으로 두되, 폭발적 트래픽이 예상되는 티켓은 운영자가 사전에 V7 경로로 수동 설정. V7은 ①대기열로 서버 진입 자체를 제한 ②Redis DECR로 재고를 원자적으로 선점해 즉시 성공/실패 응답 ③성공자에 한해서만 비동기로 DB 처리. 타임아웃 발생 시 재고를 복구하여 대기열 다음 사람에게 자동 배분. V7 설정 실수 시에도 V5가 폴백으로 서비스 다운을 방지.

**Q. 대기열을 왜 자동 감지가 아닌 운영자 수동 설정으로 구분하는가?**
→ 자동 전환은 트래픽 감지 → 판단 → 전환 사이에 딜레이가 존재하며, 그 짧은 순간에 V5가 한계를 넘어버릴 수 있음. 반면 운영자가 오픈 전 사전 설정하면 트래픽이 처음부터 올바른 경로로 흘러 안전함. 또한 최악의 경우에도 V5가 버텨주므로 판단 실수의 리스크가 낮아 수동 설정이 더 안전한 선택.

---

## 개발 순서 (권장)

> 아래 순서는 아래 "구현 설계" 섹션의 Phase와 1:1 대응된다.

1. **Phase 0** — 프로젝트 초기 세팅 (의존성, yml, kafka-docker-compose)
2. **Phase 1** — 공통 도메인 / API 뼈대 완성
3. **Phase 2~7** — V1 → V6 순서로 구현 및 버전별 부하 테스트
4. **Phase 8** — V7 구현 (Redis 선점 + 대기열 + 비동기 DB)
5. **Phase 9** — 전 버전 동일 조건 최종 테스트 + 결과 DB 저장
6. **Phase 10** — 대시보드 구현 (Thymeleaf + Chart.js)
7. **Phase 11** — docs/ 성능 비교표 및 트레이드오프 정리
8. **Phase 12** — README.md 완성 (대시보드 스크린샷 포함)

---

## 구현 설계 (Claude 세팅 가이드)

> Claude가 이 문서를 읽고 프로젝트를 세팅할 때 따라야 할 상세 스펙.
> 각 Phase를 순서대로 진행하며, Phase가 완료될 때마다 체크 후 다음 단계로 이동한다.

---

### Phase 0 — 프로젝트 초기 세팅

**목표**: 전 버전이 공유하는 뼈대 코드와 설정을 완성한다.

**환경 전제**:
- MySQL, Redis는 로컬에 직접 설치된 것을 사용 (Docker 불필요)
- Kafka만 Docker로 실행 (`kafka-docker-compose.yml` 별도 관리)
- 학습용 프로젝트이므로 모든 설정값은 yml에 하드코딩

#### 0-1. Gradle 의존성

```groovy
// build.gradle
dependencies {
    // Core
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'

    // DB
    runtimeOnly 'com.mysql:mysql-connector-j'

    // Redis
    implementation 'org.redisson:redisson-spring-boot-starter:3.27.2'

    // Kafka
    implementation 'org.springframework.kafka:spring-kafka'

    // Util
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'

    // Test
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.kafka:spring-kafka-test'
}
```

#### 0-2. application.yml

> 학습용 프로젝트이므로 환경변수 없이 yml에 직접 하드코딩한다.

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ticketing?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: create
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.MySQL8Dialect
  data:
    redis:
      host: localhost
      port: 6379
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: ticketing-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer

ticketing:
  kafka:
    topic: ticket-reservation
```

#### 0-3. kafka-docker-compose.yml (Kafka 전용)

> MySQL, Redis는 로컬에 직접 설치된 것을 사용한다.
> Kafka만 Docker로 실행한다. 파일명을 `kafka-docker-compose.yml`로 분리하여 관리한다.

```yaml
version: '3.8'
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.6.0
    container_name: zookeeper
    ports:
      - "2181:2181"
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    container_name: kafka
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
```

**실행 명령어**:
```bash
# 시작
docker-compose -f kafka-docker-compose.yml up -d

# 종료
docker-compose -f kafka-docker-compose.yml down

# 토픽 목록 확인 (정상 기동 확인용)
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --list
```

---

### Phase 1 — 도메인 & 공통 코드

**목표**: 모든 버전이 공유하는 엔티티, 레포지토리, API를 완성한다.

#### 1-1. ERD

```
Concert (공연)
  - id          BIGINT PK
  - title       VARCHAR(100)
  - totalStock  INT          -- 전체 재고
  - stock       INT          -- 현재 남은 재고
  - version     BIGINT       -- V3 낙관적 락용 (@Version)
  - createdAt   DATETIME

Reservation (예약)
  - id          BIGINT PK
  - concertId   BIGINT FK
  - userId      BIGINT
  - status      ENUM(SUCCESS, FAIL, PENDING)
  - createdAt   DATETIME
```

#### 1-2. 엔티티

```java
// Concert.java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Concert {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private int totalStock;
    private int stock;

    @Version  // V3에서 사용, 나머지 버전에는 영향 없음
    private Long version;

    public void decrease() {
        if (this.stock <= 0) throw new IllegalStateException("재고 없음");
        this.stock--;
    }

    // 테스트 데이터 초기화용
    public void resetStock() {
        this.stock = this.totalStock;
    }
}
```

```java
// Reservation.java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long concertId;
    private Long userId;

    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    @CreatedDate
    private LocalDateTime createdAt;

    public static Reservation of(Long concertId, Long userId, ReservationStatus status) {
        Reservation r = new Reservation();
        r.concertId = concertId;
        r.userId = userId;
        r.status = status;
        return r;
    }
}
```

#### 1-3. 레포지토리

```java
// ConcertRepository.java
public interface ConcertRepository extends JpaRepository<Concert, Long> {

    // V2 비관적 락
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Concert c WHERE c.id = :id")
    Optional<Concert> findByIdWithPessimisticLock(@Param("id") Long id);

    // V3 낙관적 락 (기본 findById에 @Version이 자동 적용되므로 별도 메서드 불필요)
}
```

#### 1-4. API 엔드포인트

**시나리오 A용 (경합 테스트)**:
```
POST /api/v1/concerts/{concertId}/reserve   → TicketServiceV1
POST /api/v2/concerts/{concertId}/reserve   → TicketServiceV2
POST /api/v3/concerts/{concertId}/reserve   → TicketServiceV3
POST /api/v4/concerts/{concertId}/reserve   → TicketServiceV4
POST /api/v5/concerts/{concertId}/reserve   → TicketServiceV5
POST /api/v6/concerts/{concertId}/reserve   → TicketServiceV6 (비동기)
```

**공통 유틸 API**:
```
GET  /api/concerts/{concertId}/stock        → 현재 재고 조회
POST /api/concerts/{concertId}/reset?stock={n} → 재고 초기화 (테스트용, 기본값 100)
```

**시나리오 B 전용 (실제 티켓팅 플로우)**:
```
GET  /api/concerts                          → 공연 목록 (페이징)
GET  /api/concerts/{concertId}              → 공연 상세 + 잔여석 수
POST /api/payments                          → 결제 Mock (reservationId 받아 PAID 처리)
GET  /api/reservations/{ticketToken}/status → V6 비동기 결과 폴링
```

**Reserve Request Body (공통)**:
```json
{ "userId": 1 }
```

**Reserve Response (V1~V5)**:
```json
{ "reservationId": 123, "status": "SUCCESS" }
```

**Reserve Response (V6 비동기)**:
```json
{ "ticketToken": "uuid-xxxx", "status": "PENDING" }
```

**Payment Request Body**:
```json
{ "reservationId": 123 }
```

**Payment Response**:
```json
{ "paymentId": 456, "status": "PAID" }
```

> 결제 Mock은 `Thread.sleep(100~200ms)` 랜덤 지연을 포함해 실제 PG 응답시간을 시뮬레이션한다.

**ERD 추가 — Payment**:
```
Payment
  - id            BIGINT PK
  - reservationId BIGINT FK
  - status        ENUM(PAID, FAILED)
  - createdAt     DATETIME
```

---

### Phase 2 — V1: No Lock

> 한 번에 작성할 파일: `TicketServiceV1.java` 하나.

**2-1. 서비스 작성**
```
작성 내용: reserveTicket(concertId, userId) 메서드
흐름:
  findConcertById(concertId)
    → validateStockAvailable(concert)
    → decreaseStock(concert)
    → saveReservation(concert, userId)
확인 포인트: 메서드가 4개로 분리되어 있는지
```

**2-2. 동작 확인 후 부하 테스트**
```
확인 포인트:
  - 단건 호출 → 정상 예약 되는가?
  - 시나리오 A (동시 500명) → 오버부킹 건수가 0보다 큰가? (의도된 동작)
```

---

### Phase 3 — V2: Pessimistic Lock

> 한 번에 작성할 파일: `ConcertRepository` 메서드 추가 → 확인 → `TicketServiceV2.java`.

**3-1. Repository에 비관적 락 쿼리 추가**
```
추가 내용: findByIdWithPessimisticLock(@Lock PESSIMISTIC_WRITE)
확인 포인트: 실행 시 SELECT ... FOR UPDATE 쿼리가 로그에 출력되는가?
```

**3-2. 서비스 작성**
```
작성 내용: TicketServiceV2 — findConcertWithPessimisticLock() 사용
확인 포인트: 단건 호출 정상 동작 → 동시 500명 → 오버부킹 0건인가?
```

**3-3. 데드락 시나리오 테스트**
```
작성 내용: DeadlockTest.java
흐름: 스레드 A가 concert1 → concert2 순서로 잠금
      스레드 B가 concert2 → concert1 순서로 잠금
해소: @Transactional(timeout=3) 추가
확인 포인트: 타임아웃 없이 → 데드락 발생 / 타임아웃 있으면 → 정상 해소
```

---

### Phase 4 — V3: Optimistic Lock

> 한 번에 작성할 파일: Concert 엔티티 `@Version` 확인 → `OptimisticLockRetryer` → `TicketServiceV3`.

**4-1. 재시도 헬퍼 작성**
```
파일: OptimisticLockRetryer.java
작성 내용:
  executeWithRetry(Supplier<T> action, int maxRetry)
  → ObjectOptimisticLockingFailureException 발생 시 재시도
  → 재시도마다 retryCount 카운터 증가 + INFO 로그 출력
  → maxRetry 초과 시 ReservationFailedException throw
확인 포인트: 단독 단위 테스트로 재시도 동작 확인
```

**4-2. 서비스 작성**
```
파일: TicketServiceV3.java
작성 내용: retryer.executeWithRetry(() -> reserveTicket(...), 10)
확인 포인트:
  - 단건 정상 호출
  - 동시 500명 → 오버부킹 0건
  - 로그에서 재시도 횟수 증가 확인
```

---

### Phase 5 — V4: Lettuce Spin Lock

> 한 번에 작성할 파일: `LettuceLockRepository` → 확인 → `TicketServiceV4`.

**5-1. LettuceLockRepository 작성**
```
파일: LettuceLockRepository.java
작성 내용:
  tryLock(concertId)  → SETNX + EXPIRE 3초
  releaseLock(concertId) → DELETE
확인 포인트:
  - tryLock 두 번 연속 호출 시 두 번째는 false 반환하는가?
  - releaseLock 후 tryLock 다시 성공하는가?
```

**5-2. 서비스 작성**
```
파일: TicketServiceV4.java
주의: @Transactional은 내부 reserveTicketInTransaction() 에만 적용
      락 획득/해제는 바깥 메서드에서 처리 (트랜잭션 커밋 후 락 해제 보장)

흐름:
  acquireSpinLock(concertId)        ← 락 획득될 때까지 100ms 간격 재시도
    → reserveTicketInTransaction()  ← @Transactional
    → releaseLock(concertId)        ← finally

확인 포인트:
  - 동시 500명 → 오버부킹 0건
  - 로그에서 스핀 대기 횟수 확인
```

---

### Phase 6 — V5: Redisson Pub-Sub Lock

> 한 번에 작성할 파일: `TicketServiceV5.java` 하나. Redisson은 별도 레포지토리 없이 직접 사용.

**6-1. 서비스 작성**
```
파일: TicketServiceV5.java
상수:
  LOCK_WAIT_SECONDS  = 5
  LOCK_LEASE_SECONDS = 3

흐름:
  acquireRedissonLock(concertId)
    → lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, SECONDS)
    → 실패 시 LockAcquisitionFailedException throw
  → reserveTicketInTransaction()
  → releaseLockSafely(lock)   ← lock.isHeldByCurrentThread() 확인 후 해제

확인 포인트:
  - 동시 500명 → 오버부킹 0건
  - V4 대비 Redis 부하 비교 (redis-cli INFO stats의 total_commands_processed)
```

---

### Phase 7 — V6: Kafka 비동기 대기열

> 한 번에 작성할 파일: `TicketProducer` → 확인 → `TicketConsumer` → 확인 → `TicketServiceV6`.

**7-1. TicketProducer 작성**
```
파일: TicketProducer.java
작성 내용:
  publishReservationRequest(concertId, userId)
    → ticketToken(UUID) 생성
    → ReservationRequest를 JSON으로 직렬화 → Kafka 토픽 발행
    → ticketToken 반환
확인 포인트: 발행 후 Kafka 콘솔에서 메시지 수신되는가?
  docker exec kafka kafka-console-consumer     --bootstrap-server localhost:9092 --topic ticket-reservation --from-beginning
```

**7-2. TicketConsumer 작성**
```
파일: TicketConsumer.java
설정: @KafkaListener(topicPattern = "ticket-reservation", concurrency = "1")
흐름:
  consumeReservationRequest(message)
    → JSON 역직렬화
    → reserveTicketSequentially(concertId, userId)
    → 결과(SUCCESS/FAIL)를 Redis에 저장 (key: ticketToken, TTL: 5분)
확인 포인트:
  - 메시지 순서대로 처리되는가?
  - Redis에 결과가 저장되는가? (redis-cli GET ticket:{token})
```

**7-3. TicketServiceV6 + 결과 폴링 API 작성**
```
파일: TicketServiceV6.java
흐름:
  reserveAsync(concertId, userId)
    → producer.publishReservationRequest()
    → ReservationResponse(ticketToken, PENDING) 반환

파일: ReservationStatusController.java (또는 기존 컨트롤러에 추가)
  GET /api/reservations/{ticketToken}/status
    → Redis에서 결과 조회
    → 없으면 PENDING / 있으면 SUCCESS or FAIL 반환

확인 포인트:
  - POST 예약 → PENDING 응답
  - GET 폴링 → 처리 완료 후 SUCCESS 응답
  - 동시 500명 → 오버부킹 0건
```

**토픽 설정**: partition=1, replication-factor=1 (단일 컨슈머 순차 처리 보장)

---

### Phase 8 — Gatling 부하 테스트

**파일 구조**:
```
load-test/gatling/
├── ScenarioASimulation.scala   # 시나리오 A (경합)
├── ScenarioBSimulation.scala   # 시나리오 B (처리량)
└── common/
    └── Feeders.scala           # 공통 feeder 정의
```

#### 시나리오 A — 극한 경합 시뮬레이션

```scala
// ScenarioASimulation.scala
// VERSION 환경변수로 버전 전환: -DVERSION=v1
val version = System.getProperty("VERSION", "v1")
val userFeeder = Iterator.continually(Map("userId" -> (Random.nextInt(100000) + 1)))

val reserveBody = s"""{"userId": $${userId}}"""

val scn = scenario(s"Scenario A - $${version}")
  .feed(userFeeder)
  .exec(
    http("reserve")
      .post(s"/api/$${version}/concerts/1/reserve")
      .header("Content-Type", "application/json")
      .body(StringBody(reserveBody))
      .check(status.in(200, 409, 500))
  )

setUp(
  scn.inject(
    atOnceUsers(1000)   // 500 / 1000 / 2000 교체하며 실행
  )
).protocols(httpProtocol)
```

#### 시나리오 B — 실제 티켓팅 플로우 시뮬레이션

```scala
// ScenarioBSimulation.scala
val version = System.getProperty("VERSION", "v1")
val userFeeder = Iterator.continually(Map("userId" -> (Random.nextInt(100000) + 1)))

val reserveBody  = s"""{"userId": $${userId}}"""
val paymentBody  = s"""{"reservationId": $${reservationId}}"""

val scn = scenario(s"Scenario B - $${version}")
  .feed(userFeeder)
  // Step 1: 공연 목록 조회
  .exec(http("공연 목록").get("/api/concerts").check(status.is(200)))
  .pause(1, 2)   // think time 1~2초
  // Step 2: 공연 상세 / 잔여석 확인
  .exec(http("공연 상세").get("/api/concerts/1").check(status.is(200)))
  .pause(2, 3)   // think time 2~3초
  // Step 3: 예약
  .exec(
    http("예약")
      .post(s"/api/$${version}/concerts/1/reserve")
      .header("Content-Type", "application/json")
      .body(StringBody(reserveBody))
      .check(status.in(200, 409))
      .check(jsonPath("$.reservationId").optional.saveAs("reservationId"))
  )
  // Step 4: 결제 (예약 성공한 경우에만)
  .doIf(session => session.contains("reservationId")) {
    exec(
      http("결제")
        .post("/api/payments")
        .header("Content-Type", "application/json")
        .body(StringBody(paymentBody))
        .check(status.is(200))
    )
  }

setUp(
  scn.inject(
    rampUsers(1000).during(10)   // 10초에 걸쳐 1000명 투입 (급격한 스파이크 방지)
  )
).protocols(httpProtocol)
```

**실행 명령어**:
```bash
# 시나리오 A - V1, 동시 1000명
mvn gatling:test -Dgatling.simulationClass=ScenarioASimulation -DVERSION=v1

# 시나리오 B - V5, 동시 1000명
mvn gatling:test -Dgatling.simulationClass=ScenarioBSimulation -DVERSION=v5
```

**테스트 전 필수**: `POST /api/concerts/1/reset` 으로 재고 초기화 후 실행.
- 시나리오 A: 재고 100으로 reset
- 시나리오 B: 재고 100,000으로 reset (reset API에 `stock` 파라미터 추가 필요)

**결과 수집**: Gatling 시뮬레이션 종료 후 아래 API를 호출하여 결과를 DB에 저장한다.

```
POST /api/test-results
Content-Type: application/json

{
  "version": "V1",
  "lockType": "NO_LOCK",
  "concurrentUsers": 500,
  "totalRequests": 500,
  "successCount": 500,
  "overBookingCount": 47,
  "tps": 1200.5,
  "p99ResponseMs": 320,
  "errorRate": 0.0,
  "memo": "기준선 측정 1회차"
}
```

> Gatling 리포트에서 수치를 확인 후 위 API를 수동으로 호출하거나,
> Gatling 시뮬레이션 종료 후 자동 저장하는 훅을 추가해도 된다.

---

### Phase 9 — 테스트 결과 대시보드 (Thymeleaf)

**목표**: 누적된 테스트 결과를 DB에서 조회하여 Thymeleaf 화면으로 시각화한다.

#### 9-1. TestResult 엔티티

> `@CreatedDate` 동작을 위해 메인 클래스에 `@EnableJpaAuditing`을 반드시 추가할 것.

```java
// TicketingApplication.java
@EnableJpaAuditing
@SpringBootApplication
public class TicketingApplication { ... }
```

```java
@Entity
@Getter
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestResult {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private LockVersion version;       // V1 ~ V6

    @Enumerated(EnumType.STRING)
    private LockType lockType;         // 아래 Enum 참고

    @Enumerated(EnumType.STRING)
    private ScenarioType scenarioType; // SCENARIO_A or SCENARIO_B

    private int concurrentUsers;      // 동시 사용자 수
    private int initialStock;         // 테스트 시작 시 재고 (A=100, B=100000)
    private int totalRequests;        // 총 요청 수
    private int successCount;         // 성공 건수
    private int overBookingCount;     // 오버부킹 건수 (정합성)
    private double tps;               // 초당 처리량
    private long p99ResponseMs;       // P99 응답시간 (ms)
    private double errorRate;         // 에러율 (%)
    private String memo;              // 메모 (선택)

    @CreatedDate
    private LocalDateTime testedAt;   // 테스트 시각 (자동 주입)
}
```

#### 9-2. LockType / LockVersion Enum

```java
public enum LockVersion {
    V1, V2, V3, V4, V5, V6
}

public enum LockType {
    NO_LOCK,        // V1
    PESSIMISTIC,    // V2
    OPTIMISTIC,     // V3
    LETTUCE_SPIN,   // V4
    REDISSON_PUBSUB,// V5
    KAFKA_QUEUE     // V6
}

public enum ScenarioType {
    SCENARIO_A,  // 극한 경합 (재고 100)
    SCENARIO_B   // 처리량 측정 (재고 100,000, 전체 플로우)
}
```

#### 9-3. 대시보드 API

```
GET  /dashboard              → 대시보드 메인 화면 (Thymeleaf)
GET  /api/test-results       → 전체 결과 목록 조회 (JSON)
POST /api/test-results       → 결과 저장
DELETE /api/test-results/{id} → 결과 삭제 (잘못 입력한 경우)
```

#### 9-4. 화면 구성 (`templates/dashboard/index.html`)

**섹션 1 — 필터**
- 시나리오 선택 (시나리오 A / 시나리오 B / 전체)
- 버전 선택 (V1~V6, 전체)
- 동시 사용자 수 선택 (500 / 1000 / 2000, 전체)

**섹션 2 — 요약 카드** (필터 조건 기준 최신 결과)
- 버전별 최고 TPS
- 버전별 오버부킹 건수
- 버전별 P99 응답시간

**섹션 3 — 차트** (Chart.js, CDN으로 로드)
- `오버부킹 건수` 막대 차트 — 버전별 정합성 비교 (시나리오 A 전용)
- `TPS 비교` 막대 차트 — 버전별 TPS 비교 (시나리오 선택 가능)
- `P99 응답시간` 막대 차트 — 버전별 레이턴시 비교
- `TPS 추이` 라인 차트 — 특정 버전의 반복 테스트 결과 추이
- `시나리오 A vs B TPS` 그룹 막대 차트 — 동일 버전의 두 시나리오 처리량 비교

**섹션 4 — 전체 결과 테이블**
- 컬럼: 버전 / 락 방식 / 동시 사용자 / TPS / P99 / 에러율 / 오버부킹 / 테스트 시각 / 메모
- 최신 순 정렬
- 행 삭제 버튼

#### 9-5. Thymeleaf → Chart.js 데이터 전달 방식

서버에서 렌더링된 데이터를 JS가 읽을 수 있도록 `<script>` 태그에 JSON으로 주입한다.

```html
<!-- index.html -->
<script th:inline="javascript">
  const chartData = /*[[${chartData}]]*/ {};
  // chartData = { labels: ["V1","V2",...], tps: [1200, 800, ...], p99: [...], overBooking: [...] }
</script>
<script src="/js/dashboard.js"></script>
```

```java
// DashboardController.java
@GetMapping("/dashboard")
public String dashboard(Model model) {
    List<TestResult> results = dashboardService.getLatestPerVersion();
    DashboardChartData chartData = dashboardService.buildChartData(results);
    model.addAttribute("results", results);
    model.addAttribute("chartData", chartData);
    return "dashboard/index";
}
```

#### 9-6. Chart.js 차트 설정 (dashboard.js)

```javascript
// TPS 막대 차트
new Chart(document.getElementById('tpsChart'), {
  type: 'bar',
  data: {
    labels: chartData.labels,
    datasets: [{
      label: 'TPS',
      data: chartData.tps,
      backgroundColor: ['#ef4444','#f97316','#eab308','#22c55e','#3b82f6','#8b5cf6']
    }]
  },
  options: { responsive: true, plugins: { legend: { display: false } } }
});
// P99, 오버부킹 차트도 동일 패턴으로 구성
```

---

### Phase 10 — 완료 체크리스트

```
[x] Phase 0-1: Gradle 의존성 추가 및 빌드 성공
[x] Phase 0-2: application.yml 작성 완료
[x] Phase 0-3: Kafka docker-compose 기동 및 토픽 확인

[x] Phase 1-1: Concert / Reservation / Payment 엔티티 작성
[x] Phase 1-2: Concert / Reservation / Payment Repository 작성
[x] Phase 1-3: 공통 API + 시나리오 B 전용 API 단건 호출 정상 동작

[x] Phase 2-1: TicketServiceV1 작성 (메서드 분리 확인)
[x] Phase 2-2: 단건 호출 → 시나리오 A 부하 테스트 → 오버부킹 발생 확인

[x] Phase 3-1: Repository 비관적 락 쿼리 추가 + SELECT FOR UPDATE 로그 확인
[x] Phase 3-2: TicketServiceV2 작성 + 오버부킹 0건 확인
[x] Phase 3-3: 데드락 시나리오 테스트 작성 + 타임아웃 해소 확인

[x] Phase 4-1: OptimisticLockRetryer 작성 + 단위 테스트 통과
[x] Phase 4-2: TicketServiceV3 작성 + 재시도 로그 + 오버부킹 0건 확인

[x] Phase 5-1: LettuceLockRepository 작성 + 잠금/해제 동작 확인
[x] Phase 5-2: TicketServiceV4 작성 + 트랜잭션 분리 확인 + 오버부킹 0건

[x] Phase 6-1: TicketServiceV5 작성 + 오버부킹 0건
[x] Phase 6-2: V4 vs V5 Redis 부하 비교 (total_commands_processed 기록)

[x] Phase 7-1: TicketProducer 작성 + Kafka 콘솔 수신 확인
[x] Phase 7-2: TicketConsumer 작성 + Redis 결과 저장 확인
[x] Phase 7-3: TicketServiceV6 + 폴링 API + 전체 흐름 동작 확인

[ ] Phase 8-1: Redis Sorted Set 기반 대기열 구현 + 순서 보장 확인
[ ] Phase 8-2: Redis DECR 재고 선점 + 즉시 성공/실패 응답 확인
[ ] Phase 8-3: 성공자 비동기 DB 처리 (Kafka or @Async) 동작 확인
[ ] Phase 8-4: 타임아웃 발생 시 재고 복구 → 대기열 다음 사람 배분 확인
[ ] Phase 8-5: V5 vs V7 부하 테스트 비교 (대기열 유무에 따른 서버 부하 차이)

[ ] Phase 9:   전 버전 시나리오 A + B 부하 테스트 완료 + 결과 DB 저장

[x] Phase 10-1: TestResult 엔티티 + Repository + 저장 API 동작 확인
[x] Phase 10-2: DashboardController + 대시보드 화면 렌더링 확인
[x] Phase 10-3: 차트 4종 + 필터 + 테이블 전체 동작 확인

[ ] Phase 11: docs/ 성능 비교표 + 트레이드오프 정리 (V7 포함)
[ ] Phase 12: README.md 완성 (대시보드 스크린샷 포함)
```

---

### 코딩 컨벤션 & 규칙

> Claude가 코드를 작성할 때 반드시 따라야 하는 규칙. 기능 동작보다 우선순위가 높다.

---

#### 가독성 원칙

**메서드는 한 가지 일만 한다**
- 한 메서드가 조회 + 검증 + 저장을 동시에 하면 안 됨
- 메서드 길이가 20줄을 넘으면 분리를 먼저 고민할 것

**메서드 이름은 동작을 설명한다**
```java
// Bad
public void process(Long id) { ... }

// Good
public Reservation reserveTicket(Long concertId, Long userId) { ... }
public void decreaseStock(Concert concert) { ... }
public void validateStockAvailable(Concert concert) { ... }
```

**변수 이름은 의미를 담는다**
```java
// Bad
Concert c = concertRepository.findById(id).orElseThrow();
int n = c.getStock();

// Good
Concert concert = concertRepository.findById(concertId).orElseThrow();
int remainingStock = concert.getStock();
```

**매직 넘버는 상수로 뺀다**
```java
// Bad
Thread.sleep(100);
lock.tryLock(5, 3, TimeUnit.SECONDS);

// Good
private static final long SPIN_WAIT_MS       = 100;
private static final long LOCK_WAIT_SEC      = 5;
private static final long LOCK_LEASE_SEC     = 3;
```

**조건문은 의미 있는 메서드로 추출한다**
```java
// Bad
if (concert.getStock() <= 0) { throw new IllegalStateException("재고 없음"); }

// Good
private void validateStockAvailable(Concert concert) {
    if (concert.isOutOfStock()) {
        throw new SoldOutException(concert.getId());
    }
}
```

---

#### 복잡도 원칙

**추상화는 꼭 필요한 곳에만**
- 인터페이스는 구현체가 2개 이상이거나 테스트 대역이 필요할 때만 도입
- `TicketService` 인터페이스 → V1~V6이 각각 다른 방식이므로 도입 정당함
- 그 외 불필요한 레이어(ex. ServiceImpl 패턴) 추가 금지

**예외는 명확하게 정의한다**
```java
// 커스텀 예외 클래스를 만들어 의미를 부여
public class SoldOutException extends RuntimeException { ... }
public class LockAcquisitionFailedException extends RuntimeException { ... }
public class ConcertNotFoundException extends RuntimeException { ... }
```

**Lombok은 최소한으로 사용한다**
```java
@Getter                          // OK — 필드 접근
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // OK — JPA 필수
// @Data, @Setter 사용 금지 — 불변성 깨짐
```

---

#### 로그 원칙

모든 락 관련 이벤트는 `INFO` 레벨로 출력하여 부하 테스트 분석에 활용한다.

```java
// 락 획득 / 해제
log.info("[V4] 락 획득 성공 - concertId={}", concertId);
log.info("[V4] 락 해제 - concertId={}", concertId);

// 재시도 (V3 낙관적 락)
log.info("[V3] 낙관적 락 충돌, 재시도 - concertId={}, 시도횟수={}", concertId, retryCount);

// 재고 변경
log.info("[공통] 재고 차감 - concertId={}, 차감 후 재고={}", concertId, remainingStock);

// 예약 완료
log.info("[공통] 예약 완료 - reservationId={}, userId={}", reservation.getId(), userId);
```

---

#### 구조 원칙

- 컨트롤러는 **버전별 URL로 라우팅만** 담당, 비즈니스 로직 없음
- 모든 예외는 `GlobalExceptionHandler`에서 통일 처리 (`@RestControllerAdvice`)
- 테스트용 재고 초기화 API (`/reset`)는 `@Profile("!prod")`로 프로덕션 비활성화
- `TestResult` 저장 시 필수값: `version`, `lockType`, `scenarioType`, `concurrentUsers`, `initialStock`
- 대시보드 Chart.js는 CDN(`https://cdn.jsdelivr.net/npm/chart.js`)으로 로드

---

#### 코드 작성 단위 원칙

> Claude가 한 번에 작성하는 코드의 양을 제한하여 검토 가능한 단위로 나눈다.

- **한 번에 하나의 파일만** 작성한다
- 하나의 파일 안에서도 **논리적 단계가 2개 이상이면 나눈다**
  - 예: 엔티티 먼저 → 확인 후 → 레포지토리 → 확인 후 → 서비스 순서
- 각 단계 완료 시 **"여기까지 확인해주세요"** 멘트를 반드시 남긴다
- 다음 단계는 확인 응답을 받은 후에만 진행한다

---

## 지식 정리 목차 (쉬운 것 → 어렵고 중요한 것 순서)

> 별도 마크다운 파일(`docs/knowledge/`)에 단계별로 작성 예정.
> 이 목차 순서대로 하나씩 채워나간다.

### Level 1 — 동시성 문제의 본질 (Why Lock?)

```
1-1. 동시성(Concurrency)이란 무엇인가
1-2. Race Condition — 발생 조건과 예시 코드
1-3. Lost Update vs Dirty Write vs Phantom Read
1-4. 왜 티켓팅이 동시성 문제의 교과서적 사례인가
1-5. 오버부킹 vs Lost Update — 둘의 차이
```

### Level 2 — 트랜잭션과 격리 수준 (ACID / Isolation)

```
2-1. ACID — Atomicity, Consistency, Isolation, Durability 실례
2-2. 트랜잭션 격리 수준 4단계 (READ UNCOMMITTED → SERIALIZABLE)
2-3. MySQL InnoDB의 기본 격리 수준 (REPEATABLE READ) 동작 방식
2-4. MVCC(Multi-Version Concurrency Control) — MySQL은 어떻게 동시 읽기를 처리하는가
2-5. 격리 수준과 락의 관계 — 격리 수준이 높아지면 락이 늘어나는 이유
```

### Level 3 — DB 비관적 락 (Pessimistic Lock)

```
3-1. SELECT FOR UPDATE 동작 원리 — 어떤 락을 거는가
3-2. PESSIMISTIC_READ vs PESSIMISTIC_WRITE — 공유 락 vs 배타 락
3-3. 락 대기와 타임아웃 — innodb_lock_wait_timeout
3-4. 데드락(Deadlock) — 발생 조건 4가지, 실제 시나리오
3-5. 데드락 방지 전략 — 획득 순서 통일, NOWAIT, 타임아웃
3-6. Spring @Transactional(timeout=N)과 DB 락 타임아웃의 차이
3-7. 성능 측정 결과 해석 — 2000명에서 P99 668ms의 의미
```

### Level 4 — DB 낙관적 락 (Optimistic Lock)

```
4-1. @Version 동작 원리 — Hibernate가 UPDATE WHERE version=? 를 어떻게 생성하는가
4-2. ObjectOptimisticLockingFailureException 발생 시점
4-3. 재시도 패턴 구현 — @Retryable vs 직접 구현의 트레이드오프
4-4. 낙관적 락이 유리한 조건 vs 불리한 조건
4-5. 티켓팅 도메인에서 낙관적 락이 부적합한 이유 — 실측 데이터로 증명
4-6. 재시도 폭발(Retry Storm) — 충돌률이 높을 때 지수적으로 악화되는 이유
```

### Level 5 — Redis 기초와 Lettuce Spin Lock

```
5-1. Redis를 분산 락에 사용하는 이유 — DB 락과의 차이
5-2. SETNX + EXPIRE 원자성 문제 — 왜 두 명령을 분리하면 안 되는가
5-3. SET key value NX EX seconds — 단일 명령으로 원자적 처리
5-4. Spin Lock 구현 — while(!tryLock) + sleep 패턴
5-5. 락 해제(DELETE) 시 주인 확인 — 왜 UUID 값 비교가 필요한가 (LUA Script)
5-6. 타임아웃 설정 미스 시나리오 — leaseTime < 트랜잭션 소요시간이면?
5-7. Lettuce Spin Lock 성능 측정 결과 — 스핀 대기가 TPS에 미치는 영향
```

### Level 6 — Redisson Pub-Sub Lock 내부 동작

```
6-1. Pub-Sub 방식이 Spin Lock과 근본적으로 다른 점
6-2. Redisson RLock 내부 구조 — 락 획득 LUA Script
6-3. Pub-Sub 채널 구독 → 락 해제 이벤트 수신 → 재시도 흐름
6-4. Watchdog(자동 갱신) — 트랜잭션이 길어질 때 락 만료 방지
6-5. tryLock(waitTime, leaseTime, TimeUnit) 파라미터 의미
6-6. Redisson vs Lettuce 실측 비교 — Redis 명령어 수 vs TPS 역설 분석
6-7. 분산 락 고급 주제 — Fencing Token, Redlock 알고리즘, 클럭 드리프트
```

### Level 7 — @Transactional 심화 + Self-Invocation 문제

```
7-1. @Transactional 동작 원리 — CGLIB 프록시, AOP 인터셉터
7-2. Self-Invocation 문제 — this.method()는 왜 @Transactional이 적용 안 되는가
7-3. Self-Invocation 해결법 3가지 — @Lazy 자기 주입 vs ApplicationContext vs Refactoring
7-4. 왜 분산 락과 @Transactional을 분리해야 하는가 — 커밋 순서와 락 해제 순서
7-5. @Transactional 전파 옵션 — REQUIRES_NEW로 락 범위를 최소화하는 패턴
7-6. JPA 영속성 컨텍스트 — 1차 캐시, 더티 체킹, 락과의 상호작용
```

### Level 8 — Kafka 비동기 대기열

```
8-1. Kafka 기본 개념 — Topic, Partition, Offset, Consumer Group
8-2. 단일 파티션 + 단일 컨슈머 = 순차 처리 보장 원리
8-3. Producer 설계 — acks=all, idempotent producer, 재발행 위험
8-4. Consumer 설계 — auto.commit의 위험, 수동 커밋 패턴
8-5. V6 구현 흐름 — 발행 → PENDING 반환 → 컨슈머 처리 → Redis 저장 → 폴링
8-6. 비동기 UX 설계 — 폴링 vs SSE(Server-Sent Events) 트레이드오프
8-7. 확장 전략 — 파티션 수 증가 + concertId를 파티션 키로 사용
8-8. 카프카 vs Redis Queue vs DB Queue — 언제 무엇을 선택하는가
```

### Level 9 — Spring Boot 4.x 특이사항 (이 프로젝트에서 겪은 것)

```
9-1. Spring Boot 4.x에서 Kafka 자동설정 제거 — @EnableKafka와 수동 Bean 등록 필요
9-2. Jackson 3.x 패키지 변경 — com.fasterxml → tools.jackson
9-3. KafkaListenerContainerFactory 수동 구성
9-4. Spring Framework 7.x 변경사항 개요
```

### Level 10 — Gatling 부하 테스트 설계와 결과 해석

```
10-1. Gatling DSL 핵심 — scenario, exec, pause, feed, check, doIf
10-2. 부하 주입 방식 — atOnceUsers vs rampUsers vs constantUsersPerSec
10-3. 시나리오 A (극한 경합) 설계 이유 — atOnceUsers + 재고 100장
10-4. 시나리오 B (실제 흐름) 설계 이유 — rampUsers + think time + 4단계 플로우
10-5. TPS / P99 / 에러율 지표 의미와 해석 방법
10-6. V4 꼬리 레이턴시 폭발 원인 분석 — 왜 2000명에서 P99 13.7초인가
10-7. V4 vs V5 TPS 역설 분석 — Redis 명령어 3.3배 많은데 TPS 3배 높은 이유
10-8. V6 Gatling 측정의 함정 — PENDING 응답은 실제 처리량이 아님
```

### Level 12 — Circuit Breaker + Graceful Degradation

```
12-1. Circuit Breaker란 무엇인가 — 왜 필요한가 (Cascading Failure 방지)
12-2. Circuit Breaker 세 가지 상태 — CLOSED / OPEN / HALF_OPEN
12-3. Resilience4j 설정값 상세 해설 — minimumNumberOfCalls 함정 포함
12-4. TicketServiceV5CB 코드 흐름 상세 분석
12-5. Graceful Degradation — Fail-Fast vs 폴백 체인
12-6. CircuitBreakerStatsHolder — 관찰 가능성(Observability)
12-7. 프로그래매틱 API vs 어노테이션 방식 선택 이유
12-8. 실무 적용 시 고려사항 — 다중 인스턴스 한계, 설정값 결정 방법
12-9. 면접 Q&A 종합
```

---

### Level 11 — 면접 Q&A 전체 정리

```
11-1. [DB] 비관적 락 vs 낙관적 락 — 선택 기준과 실측 근거
11-2. [DB] 데드락을 어떻게 방지했는가
11-3. [Redis] Lettuce 대신 Redisson을 선택한 이유 — 실측 데이터 포함
11-4. [Redis] 분산 락에서 락 해제 시 주인 확인이 필요한 이유
11-5. [Redis] 타임아웃 설정은 어떻게 결정했는가
11-6. [Kafka] 단일 파티션으로 순차 처리를 보장한 이유
11-7. [Kafka] 컨슈머가 처리 중 실패하면 어떻게 되는가 (재처리 전략)
11-8. [Kafka] 처리량을 더 늘리려면 어떻게 설계를 바꾸겠는가
11-9. [Spring] @Transactional과 분산 락을 함께 쓸 때 왜 순서가 중요한가
11-10. [Spring] Self-Invocation 문제를 어떻게 해결했는가
11-11. [성능] V4보다 V5의 TPS가 왜 더 높은가 (Redis 명령어는 더 많은데)
11-12. [성능] P99가 Mean보다 중요한 지표인 경우는 언제인가
11-13. [설계] 낙관적 락이 티켓팅 도메인에 부적합한 이유를 데이터로 설명하라
11-14. [설계] V6 Kafka 방식의 단점과 실무 UX 해결 방안
11-15. [설계] 이 프로젝트를 MSA로 전환한다면 어떻게 설계하겠는가
11-16. [종합] 지금 당장 실무에 배포한다면 어떤 버전을 선택하겠는가, 그 이유는
```

---

## 지식 문서 심화 섹션 추가 진행 상태

> 각 Level 문서에 "심화 — 추가 세부 지식" 섹션을 추가 중.
> 각 섹션은 2~3개의 심화 소주제로 구성되며, 면접 답변 스크립트를 포함한다.

### 완료 (Level 1~7)

| Level | 추가된 심화 섹션 |
|-------|----------------|
| Level 1 | 1-6 JMM/캐시 가시성, 1-7 Write Skew, 1-8 임계구역/상호배제 |
| Level 2 | 2-6 갭 락(Gap Lock)/넥스트키 락, 2-7 MVCC와 Snapshot의 한계, 2-8 Phantom Read 재현 실험 |
| Level 3 | 3-8 InnoDB 락 모니터링(information_schema), 3-9 SKIP LOCKED, 3-10 Covering Index와 락 범위 |
| Level 4 | 4-7 @Retry와 직접 구현 비교, 4-8 ABA 문제, 4-9 CAS 연산과 @Version의 공통점 |
| Level 5 | 5-8 Lettuce vs Jedis 커넥션 모델, 5-9 Redis Cluster와 해시 슬롯 함정, 5-10 Lua Script 원자성 |
| Level 6 | 6-8 Redisson FairLock, 6-9 RReadWriteLock, 6-10 Thundering Herd 완화 |
| Level 7 | 7-7 @TransactionalEventListener(AFTER_COMMIT), 7-8 readOnly=true 최적화, 7-9 TransactionSynchronizationManager |

### 대기 (Level 8~11)

| Level | 추가 예정 심화 섹션 |
|-------|------------------|
| Level 8 | 8-9 Transactional Outbox Pattern, 8-10 Dead Letter Topic(DLT), 8-11 Consumer Rebalancing |
| Level 9 | 9-7 Virtual Threads, 9-8 GraalVM Native Image, 9-9 Spring Boot Actuator 모니터링 |
| Level 10 | 10-9 JVM 워밍업 함정, 10-10 용량 계획(Capacity Planning) |
| Level 11 | 11-17 꼬리 질문 모음 — 각 주제별 심화 반격 질문 |
