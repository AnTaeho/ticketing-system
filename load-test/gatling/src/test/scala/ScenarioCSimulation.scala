import io.gatling.core.Predef._

import scala.concurrent.duration._

/**
 * 시나리오 C — 오픈 러시(S1, S3, S4가 같은 유입을 쓴다)
 *
 * 흐름: 로그인 → 대기열 진입 → PROCESSING까지 1초 간격 폴링 → 생존 신호 → 좌석맵 → 2석 잡기 → 떠남 → 결제
 *       잡기가 409면 좌석맵을 다시 받아 한 번만 다시 잡는다.
 * 좌석: 10명 중 7명은 A구역 1열(인기석 20석)의 연속 2석, 3명은 무작위 빈자리 2석을 고른다.
 * 잡기는 201만 OK다. 결제는 200(확정)과 202(결과 대기)를 OK로 센다. 결제사 결과는 앱의 fake-pg 설정이 정한다.
 * 주소를 BASE_URLS로 둘 이상 주면 사용자마다 번갈아 쓴다(S4).
 *
 * 실행(회차 생성과 수치 정리는 load-test/run-booking.sh가 한다):
 *   mvn gatling:test -Dgatling.simulationClass=ScenarioCSimulation -DPERFORMANCE_ID=123 -DUSERS=2000 -DRAMP_SECONDS=10
 */
class ScenarioCSimulation extends Simulation {

  private val users         = BookingSteps.intProp("USERS", 2000)
  private val rampSeconds   = BookingSteps.intProp("RAMP_SECONDS", 10)
  private val performanceId = BookingSteps.performanceId
  private val userLog       = new UserLog(sys.props.get("USER_LOG"))

  private val scn = scenario(s"Scenario C - booking - ${users}users")
    .feed(BookingSteps.userFeeder(performanceId))
    .exec(BookingSteps.booking(BookingSteps.perfPath(performanceId), 1.second, 1.second, 300.seconds,
      maxRetries = 1, stopWhenSoldOut = false, userLog))

  setUp(
    scn.inject(rampUsers(users).during(rampSeconds.seconds))
  ).protocols(BookingSteps.protocol)

  after {
    userLog.write()
  }
}
