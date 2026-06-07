import common.Feeders
import io.gatling.core.Predef._
import io.gatling.http.Predef._

/**
 * 시나리오 A — 극한 경합 (정합성 검증)
 *
 * 목적: 소량의 티켓(100장)에 대규모 인원이 동시에 몰리는 상황에서
 *       락 방식별 오버부킹 발생 여부와 에러율을 검증한다.
 *
 * 실행 전 필수:
 *   curl -X POST "http://localhost:8080/api/concerts/1/reset?stock=100"
 *
 * 실행 예시:
 *   mvn gatling:test -Dgatling.simulationClass=ScenarioASimulation -DVERSION=v1 -DUSERS=500
 *   mvn gatling:test -Dgatling.simulationClass=ScenarioASimulation -DVERSION=v5 -DUSERS=1000
 */
class ScenarioASimulation extends Simulation {

  // -DVERSION=v1 ~ v6 (기본값: v1)
  val version: String = System.getProperty("VERSION", "v1")
  // -DUSERS=500 | 1000 | 2000 (기본값: 500)
  val users: Int = System.getProperty("USERS", "500").toInt

  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  val scn = scenario(s"Scenario A - $version - ${users}users")
    .feed(Feeders.userFeeder)
    .exec(
      http("예약 요청")
        .post(s"/api/$version/concerts/1/reserve")
        .body(StringBody("""{"userId": #{userId}}"""))
        .check(status.in(200, 409, 500))
        // V6 비동기: ticketToken 저장 (폴링 확장 시 활용)
        .check(jsonPath("$.ticketToken").optional.saveAs("ticketToken"))
    )

  setUp(
    scn.inject(atOnceUsers(users))
  ).protocols(httpProtocol)
    .assertions(
      global.successfulRequests.percent.gte(0) // 모든 응답 허용 (오버부킹 측정 목적)
    )
}
