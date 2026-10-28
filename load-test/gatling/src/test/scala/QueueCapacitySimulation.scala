import io.gatling.core.Predef._

import scala.concurrent.duration._

/**
 * 시나리오 S2 — 대기열 정원 운영
 *
 * 흐름: 로그인 → 대기열 진입 → PROCESSING까지 1~2초 간격 폴링 → 생존 신호 → 좌석맵 → 2석 잡기 → 떠남 → 결제
 *       잡기가 409면 좌석맵을 다시 받아 3번까지 다시 잡고, 빈자리가 2석 미만이면(매진) 포기한다.
 *       MAX_WAIT_SECONDS가 지나도 입장하지 못하면 떠남을 보내고 포기한다.
 * 좌석 고르기와 결제는 시나리오 C와 같다(BookingSteps).
 *
 * 실행: mvn gatling:test -Dgatling.simulationClass=QueueCapacitySimulation -DPERFORMANCE_ID=123 -DUSERS=5000 -DRAMP_SECONDS=20
 */
class QueueCapacitySimulation extends Simulation {

  private val users          = BookingSteps.intProp("USERS", 5000)
  private val rampSeconds    = BookingSteps.intProp("RAMP_SECONDS", 20)
  private val maxWaitSeconds = BookingSteps.intProp("MAX_WAIT_SECONDS", 120)
  private val performanceId  = BookingSteps.performanceId
  private val userLog        = new UserLog(sys.props.get("USER_LOG"))

  private val scn = scenario(s"S2 - queue capacity - ${users}users")
    .feed(BookingSteps.userFeeder(performanceId))
    .exec(BookingSteps.booking(BookingSteps.perfPath(performanceId), 1.second, 2.seconds, maxWaitSeconds.seconds,
      maxRetries = 3, stopWhenSoldOut = true, userLog))

  setUp(
    scn.inject(rampUsers(users).during(rampSeconds.seconds))
  ).protocols(BookingSteps.protocol)

  after {
    userLog.write()
  }
}
