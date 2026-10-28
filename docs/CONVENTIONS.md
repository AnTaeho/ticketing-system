# 코드 규약

이 저장소를 사람이 고쳐 나갈 때 지키는 규칙이다. 새 코드는 이 규칙을 따르고, 규칙과 다른 코드를 만나면 고치거나 이유를 한 줄 주석으로 남긴다.

## 1. 패키지

기능별 패키지를 `com.example.ticketing` 바로 아래에 두고, 기능 안은 역할별 하위 패키지로 나눈다.

```
com.example.ticketing
├── common/            여러 기능이 함께 쓰는 것 (error, web, redis, jdbc, time, metrics, log, config)
├── user/  show/  seat/  queue/  hold/  payment/  booking/  outbox/  notification/  ops/
│   ├── controller/    HTTP 진입점
│   ├── dto/           계층 사이를 오가는 record
│   ├── service/       흐름과 트랜잭션
│   ├── repository/    DB·Redis 접근
│   ├── domain/        엔티티, 상태 enum, 순수 규칙
│   └── client/        외부 시스템 호출 (payment만)
├── fakepg/            앱 안에 둔 가짜 결제사 (같은 하위 구조)
└── web/controller/    화면 컨트롤러
```

| 하위 패키지 | 들어가는 것 | 의존해도 되는 곳 |
|---|---|---|
| `controller` | `*Controller`, 컨트롤러 전용 변환기 | 같은 기능의 `service`·`dto`, `common` |
| `dto` | `*Request`, `*Command`, `*Result`, `*Response`, 흐름 결과 record | 같은 기능의 `domain`, `common` |
| `service` | `*Service`, `*Transaction`, `*Reader`, `*Scheduler`, `*Handler` | 같은 기능의 `repository`·`domain`·`dto`·`client`, 다른 기능의 `service`·`dto`, `common` |
| `repository` | `*Repository`(JPA), `*JdbcRepository`(JDBC), `*RedisStore`(Redis) | 같은 기능의 `domain`, `common` |
| `domain` | 엔티티, 상태 enum, 값 record, 순수 규칙 | `common`만 |
| `client` | 외부 시스템 클라이언트와 그 결과 타입 | `common` |

- 다른 기능의 `controller`나 `repository`를 직접 부르지 않는다. 필요하면 그 기능의 `service`를 부른다.
- 두 기능이 서로를 부르면(순환) 한쪽으로 몰거나 공통 개념을 `common`으로 뺀다.

## 2. 클래스 역할과 이름

| 접미사 | 하는 일 | 하지 않는 일 |
|---|---|---|
| `*Controller` | HTTP 요청을 `*Command`로 바꾸고, `*Result`를 `*Response`로 바꾼다. 상태 코드를 고른다 | 판정, SQL, Redis |
| `*Service` | 한 유스케이스의 흐름을 짠다. 트랜잭션을 열지 않는다. 커밋 뒤 Redis 반영과 외부 호출을 여기서 한다 | SQL |
| `*Transaction` | 쓰기 트랜잭션 한 단위. 락, 조건부 UPDATE, 같은 트랜잭션의 Outbox 기록 | 외부 호출, Redis |
| `*Reader` | 읽기 전용 조회와 조립 | 쓰기 |
| `*Scheduler` | 주기 실행의 진입점. 집고, 건마다 위임하고, 실패를 기록한다 | 업무 판정 |
| `*Repository` | JPA. 단건 저장·조회와 native 조건부 UPDATE | 여러 테이블 조립 |
| `*JdbcRepository` | `NamedParameterJdbcTemplate`. `SKIP LOCKED` 집기, 다건·조인 조회, 대량 INSERT | 업무 판정 |
| `*RedisStore` | Redis 읽기·쓰기와 Lua 실행. Redis 예외는 그대로 던진다 | 장애 정책 |
| `*Client` | 외부 시스템 호출. 실패를 결과 값으로 돌려준다 | 재시도 정책 |

- 메서드 이름은 하는 일을 그대로 쓴다. `boolean`은 이름 그대로의 뜻일 때만 돌려주고, 갈래가 셋 이상이거나 "모름"이 섞이면 enum을 돌려준다.
- 상태 전이 메서드는 `출발To도착`(`heldToPaying`, `payingToConfirmed`)으로 짓는다.
- 인자 순서는 `userId, performanceId, …`로 통일한다.

## 3. 데이터 전달은 DTO로

계층 사이에서 오가는 값은 모두 이름 있는 record다.

| 경계 | 타입 | 위치 |
|---|---|---|
| HTTP → Controller | `*Request` (Bean Validation, `toCommand()`) | `dto` |
| Controller → Service | `*Command` (값이 둘 이상일 때. id 하나는 그대로 넘긴다) | `dto` |
| Service → Controller | `*Result` | `dto` |
| Controller → HTTP | `*Response` (`from(result)`) | `dto` |
| `*Transaction` → `*Service` | 결과를 뜻하는 이름의 record (예: `HoldPlacement`, `PaymentConclusion`) | `dto` |
| Outbox 작업 | 작업 종류별 payload record | `outbox/dto` |

- 엔티티는 `service` 밖으로 나가지 않는다. 엔티티에서 DTO를 만들 때는 `*Result.from(entity)` 한 곳에서만 한다.
- `Map`은 오류 `detail`에만 쓴다. JSON을 문자열 키로 읽고 쓰지 않는다.
- 같은 값은 같은 이름으로 부른다. (대기열 토큰은 어디서나 `queueToken`)

## 4. 트랜잭션과 정합성

- 좌석 배정의 최종 판정은 DB 조건부 UPDATE의 영향 행 수다. Redis는 DB 앞의 게이트다.
- 상태 전이는 `repository`의 native 조건부 UPDATE로만 한다. 엔티티 변경 감지로 상태를 바꾸지 않는다. 그래서 엔티티에는 setter와 상태 변경 메서드가 없다. 상태에 대한 판정(예: "결제가 아직 열려 있나")은 상태 enum에 둔다.
- 영향 행 수는 `RowCounts`로 확인한다. 기대와 다르면 업무 실패는 `ApiException`, 불변식 위반은 `IllegalStateException`이다.
- 상태를 바꾸는 트랜잭션은 `READ_COMMITTED`다. 락 순서는 부모 행(app_user → seat_hold/booking) → 좌석(id 오름차순)이다.
- `SKIP LOCKED` 집기는 상태 변경과 같은 트랜잭션에서 한다.
- 트랜잭션은 `*Transaction`의 선언형 `@Transactional`로만 연다. `TransactionTemplate`은 쓰지 않는다.

## 5. 시간

- 만료·마감 판정은 SQL `NOW(6)`이다. "지금 + 기간"은 SQL에서 바인드한 기간으로 계산한다.
- 자바에서 업무 시각이 필요하면 `DbClock`에서 받는다. 경과 시간(캐시 신선도, 간격)은 주입한 `LongSupplier` 나노 시계로 잰다. `System.currentTimeMillis()`·`nanoTime()`을 직접 부르지 않는다.

## 6. Redis

- 키는 `common.redis.RedisKeys`에서만 만든다. 한 회차의 키는 해시 태그 `{p:<회차>}`를 붙인다.
- Lua 스크립트는 `resources/scripts/`에 두고 `RedisScripts`로 한 번만 읽는다. 스크립트 머리에 KEYS, ARGV, 반환을 적는다.
- Redis 장애 판별은 `RedisFailures`로 한다.
- 커밋 뒤의 Redis 반영(게이트 해제·연장·확정, 토큰 소모)은 실패해도 사용자 응답을 바꾸지 않는다. 실패는 카운터와 `ThrottledWarn`으로 남긴다. 게이트가 꺼졌는지는 반영을 맡은 빈이 판단한다.

## 7. 오류

- 사용자에게 돌려줄 실패는 `ApiException(ErrorCode, detail)`이다. 화면에 보일 문장은 `ErrorCode`에만 쓴다.
- `detail`의 `reason`은 영문 대문자 코드다 (`COUNT`, `DUPLICATE`).
- 결과 값을 예외로 바꾸는 일은 `*Service`에서 한다. `*Transaction`과 `*Client`는 결과 값을 돌려준다.
- 입력 형식 검증은 `*Request`의 Bean Validation과 `toCommand()`에서 한다. 업무 규칙 검증은 `service`에서 한다.

## 8. 설정

- 설정은 `TicketingProperties`(접두사 `ticketing`) 하나에 둔다. 기본값은 `application.yml`에만 적는다.
- 주기(`@Scheduled`)에 쓰는 값만 ms 정수이고, 나머지 기간은 `Duration`이다.
- 스케줄러를 끄는 스위치(`*-enabled`)도 설정 record와 `application.yml`에 적는다.
- 설정 값은 생성자에서 한 번 꺼내 `final` 필드에 둔다.

## 9. 메트릭과 로그

- 메트릭 이름과 태그는 `common.metrics.Metrics`의 상수로만 쓴다. 이름은 `ticketing.<영역>.<사건>`, 태그 키는 결과를 나타낼 때 `result`를 쓴다.
- 카운터는 생성자에서 미리 등록한다.
- 로그는 `[영역] 문장 key=value` 형식이다. 반복될 수 있는 실패(장애 중 매 요청·매 주기)는 `ThrottledWarn`으로 남긴다. 예상하지 못한 예외는 스택과 함께 남긴다.

## 10. 주석

- 주석은 한두 줄로, 코드만 보고는 알 수 없는 이유를 쓴다. 결정 경위, 표, 목록은 넣지 않는다.
- 동작을 바꾸면 주석도 같이 고친다. 틀린 주석은 없는 주석보다 나쁘다.

## 11. 테스트

- 통합 테스트는 `@IntegrationTest`(MOCK) 또는 `@HttpIntegrationTest`(RANDOM_PORT) 메타 애너테이션을 쓴다. 테스트는 전용 DB `ticketing_booking_test`와 Redis DB 1을 쓴다.
- 설정을 바꾼 대상을 만들 때는 `@TestPropertySource`로 컨텍스트를 늘리지 않고, `TestProperties`로 바꾼 설정을 만들어 `new`로 조립한다.
- 데이터는 운영 경로(`Fixtures`)로 만든다. 픽스처가 만든 회차는 픽스처가 기억했다가 정리한다.
- 동시성 테스트는 `Concurrently` 러너를 쓴다. 출발 전 모두 준비시키고, 시간 제한을 두고, 예기치 못한 예외가 0인지 단언한다.
- 준비, 실행, 검증은 빈 줄로 나눈다. `@DisplayName`은 "조건이면 결과다" 한 문장이다.
- 테스트 수와 단언 강도를 줄이는 리팩터링은 하지 않는다. 표준 출력에 찍지 않는다.

## 12. 화면 (Thymeleaf + 순수 JS)

- 전역은 `window.bk` 하나다. 화면 JS는 IIFE 하나로 감싸고 `bk`의 공용 함수(요청, 폴링, 머리 채우기, 저장소 키, 링크)를 쓴다.
- 폴링은 `bk.poller`로 한다. 탭이 숨으면 멈추고, 늦게 온 응답은 버린다.
- 색과 글자 크기는 `booking.css`의 `:root` 토큰으로만 쓴다. 한 화면만 쓰는 규칙은 그 화면 CSS에 둔다.
- 화면 글은 쉬운 말로 쓰고, 만든 쪽 용어(선점, 게이트, 대사, Outbox, PG)는 쓰지 않는다. 운영 현황 화면은 예외로 용어를 쓰되 표마다 설명 한 줄을 둔다.
