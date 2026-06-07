import common.Feeders
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

/**
 * 시나리오 B — 처리량 측정 (실제 티켓팅 플로우)
 *
 * 목적: 재고가 충분한 상황에서 실제 사용자 행동 흐름을 시뮬레이션하여
 *       락 방식별 실질 처리량(TPS)과 레이턴시를 비교한다.
 *
 * 플로우:
 *   1. GET  /api/concerts           → 공연 목록 조회       (think: 1~2s)
 *   2. GET  /api/concerts/1         → 공연 상세/잔여석      (think: 2~3s)
 *   3. POST /api/v{n}/concerts/1/reserve → 예약
 *   4. POST /api/payments           → 결제 (reservationId 있을 때만)
 *
 * 실행 전 필수:
 *   curl -X POST "http://localhost:8080/api/concerts/1/reset?stock=100000"
 *
 * 실행 예시:
 *   mvn gatling:test -Dgatling.simulationClass=ScenarioBSimulation -DVERSION=v1 -DUSERS=500
 *   mvn gatling:test -Dgatling.simulationClass=ScenarioBSimulation -DVERSION=v5 -DUSERS=1000
 */
class ScenarioBSimulation extends Simulation {

  val version: String = System.getProperty("VERSION", "v1")
  val users: Int      = System.getProperty("USERS", "500").toInt

  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  val scn = scenario(s"Scenario B - $version - ${users}users")
    .feed(Feeders.userFeeder)

    // Step 1: 공연 목록 조회
    .exec(
      http("1. 공연 목록")
        .get("/api/concerts")
        .check(status.is(200))
    )
    .pause(1, 2)

    // Step 2: 공연 상세 조회
    .exec(
      http("2. 공연 상세")
        .get("/api/concerts/1")
        .check(status.is(200))
    )
    .pause(2, 3)

    // Step 3: 예약 요청
    .exec(
      http("3. 예약")
        .post(s"/api/$version/concerts/1/reserve")
        .body(StringBody("""{"userId": #{userId}}"""))
        .check(status.in(200, 409))
        .check(jsonPath("$.reservationId").optional.saveAs("reservationId"))
    )

    // Step 4: 결제 (예약 성공 시에만)
    .doIf(session => session.contains("reservationId")) {
      exec(
        http("4. 결제")
          .post("/api/payments")
          .body(StringBody("""{"reservationId": #{reservationId}}"""))
          .check(status.is(200))
      )
    }

  setUp(
    scn.inject(
      rampUsers(users).during(10.seconds) // 10초에 걸쳐 점진적 투입 (급격한 스파이크 방지)
    )
  ).protocols(httpProtocol)
    .assertions(
      global.successfulRequests.percent.gte(90) // 성공률 90% 이상
    )
}
