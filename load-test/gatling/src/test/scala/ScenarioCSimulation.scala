import io.gatling.core.Predef._
import io.gatling.http.Predef._

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.util.Random

/**
 * 시나리오 C — 지정석 예매(V7) 쏠림 부하
 *
 * 흐름: 로그인 → 대기열 진입 → PROCESSING까지 폴링 → 좌석맵 → 선점 → 성공하면 결제(SUCCESS)
 *       선점이 실패하면 좌석맵을 다시 받아 한 번만 재시도한다.
 * 좌석: 10명 중 7명은 A구역 1열(인기석 20석)의 연속 2석, 3명은 무작위 빈자리 2석을 고른다.
 *       인기석 사용자는 좌석맵에 빈 연속 2석이 있으면 그중에서, 없으면 아무 연속 2석이나 누른다.
 * 선점은 201만 OK로 센다. 409·503은 KO로 남아 오류 목록에서 상태 코드별로 보인다.
 * 결제는 200(확정)만 OK로 센다. 202(결과 대기)는 KO로 남는다.
 * 요청 이름은 request-names.properties에서 읽는다(RequestNames).
 *
 * 실행(회차 생성과 수치 정리는 load-test/run-booking.sh가 한다):
 *   mvn gatling:test -Dgatling.simulationClass=ScenarioCSimulation -DPERFORMANCE_ID=123 -DUSERS=2000 -DRAMP_SECONDS=10
 */
class ScenarioCSimulation extends Simulation {

  val baseUrl: String     = System.getProperty("BASE_URL", "http://localhost:8080")
  val users: Int          = System.getProperty("USERS", "2000").toInt
  val rampSeconds: Int    = System.getProperty("RAMP_SECONDS", "10").toInt
  val performanceId: Long = Option(System.getProperty("PERFORMANCE_ID")).map(_.toLong)
    .getOrElse(throw new IllegalArgumentException("-DPERFORMANCE_ID가 필요하다"))

  val MaxQueuePolls = 300

  // 좌석맵의 좌석 상태 한 글자(A/H/S) 중 빈자리. 구역 이름 A와는 상관없다
  private val SeatAvailable = "A"

  // 좌석맵은 구역·열 순서로 오므로 첫 구역의 첫 열이 인기석(A구역 1열)이다
  private val HotRowSeats = "$.sections[0].rows[0].seats[*]"

  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  // 닉네임에 회차 ID를 넣어 회차마다 새 사용자로 시작한다
  private val sequence = new AtomicInteger()
  val userFeeder: Iterator[Map[String, Any]] = Iterator.continually {
    val n = sequence.incrementAndGet()
    Map("nickname" -> s"lt$performanceId-$n", "hot" -> (n % 10 < 7))
  }

  private val perf = s"/api/performances/$performanceId"

  private def seatMap(name: String) =
    http(name)
      .get(s"$perf/seats")
      .header("X-User-Id", "#{userId}")
      .check(status.is(200))
      .check(jsonPath(s"$HotRowSeats.id").ofType[Long].findAll.saveAs("hotIds"))
      .check(jsonPath(s"$HotRowSeats.s").findAll.saveAs("hotStates"))
      .check(jsonPath(s"$$..seats[?(@.s == '$SeatAvailable')].id").ofType[Long].findAll.optional.saveAs("freeIds"))

  private def hold(name: String) =
    http(name)
      .post(s"$perf/holds")
      .header("X-User-Id", "#{userId}")
      .body(StringBody("""{"seatIds": #{seatIds}, "queueToken": "#{queueToken}"}"""))
      .check(status.is(201))
      .check(jsonPath("$.holdId").ofType[Long].optional.saveAs("holdId"))

  // 재시도에서는 인기석 사용자도 빈 연속 2석이 없으면 아무 빈자리 2석으로 물러난다
  private def chooseSeats(retry: Boolean)(session: Session): Session = {
    val hotIds    = session("hotIds").as[Seq[Long]]
    val hotStates = session("hotStates").as[Seq[String]]
    val freeIds   = session("freeIds").asOption[Seq[Long]].getOrElse(Seq.empty)
    val freePairs = (0 until hotIds.size - 1)
      .filter(i => hotStates(i) == SeatAvailable && hotStates(i + 1) == SeatAvailable)

    def pair(i: Int): Seq[Long] = Seq(hotIds(i), hotIds(i + 1))
    def randomFree: Option[Seq[Long]] =
      if (freeIds.size >= 2) Some(Random.shuffle(freeIds).take(2)) else None

    val picked: Option[Seq[Long]] =
      if (session("hot").as[Boolean]) {
        if (freePairs.nonEmpty) Some(pair(freePairs(Random.nextInt(freePairs.size))))
        else if (!retry) Some(pair(Random.nextInt(hotIds.size - 1)))
        else randomFree
      } else randomFree

    picked match {
      case Some(ids) => session.set("seatIds", ids.mkString("[", ",", "]"))
      case None      => session.remove("seatIds")
    }
  }

  val scn = scenario(s"Scenario C - booking - ${users}users")
    .feed(userFeeder)
    .exec(
      http(RequestNames.Login)
        .post("/api/demo/login")
        .body(StringBody("""{"nickname": "#{nickname}"}"""))
        .check(status.is(200))
        .check(jsonPath("$.userId").ofType[Long].saveAs("userId"))
    )
    .exitHereIfFailed
    .exec(
      http(RequestNames.QueueEnter)
        .post(s"$perf/queue/tokens")
        .header("X-User-Id", "#{userId}")
        .check(status.is(200))
        .check(jsonPath("$.token").saveAs("queueToken"))
        .check(jsonPath("$.status").saveAs("queueStatus"))
    )
    .exitHereIfFailed
    .asLongAs(s => s("queueStatus").as[String] == "WAITING" && s("polls").asOption[Int].getOrElse(0) < MaxQueuePolls, "polls") {
      pause(1.second)
        .exec(
          http(RequestNames.QueuePoll)
            .get(s"$perf/queue/tokens/#{queueToken}")
            .header("X-User-Id", "#{userId}")
            .check(status.is(200))
            .check(jsonPath("$.status").saveAs("queueStatus"))
        )
    }
    .exitHereIf(s => s("queueStatus").as[String] != "PROCESSING")
    .exec(seatMap(RequestNames.SeatMap))
    .exitHereIfFailed
    .exec(chooseSeats(retry = false) _)
    // 좌석이 다 팔려 고를 자리가 없으면 선점하지 않고 끝낸다
    .doIf(_.contains("seatIds")) {
      exec(hold(RequestNames.Hold))
        .doIf(s => !s.contains("holdId")) {
          exec(_.removeAll("hotIds", "hotStates", "freeIds"))
            .exec(seatMap(RequestNames.SeatMapRetry))
            .doIf(s => s("hotIds").asOption[Seq[Long]].isDefined) {
              exec(chooseSeats(retry = true) _)
                .doIf(_.contains("seatIds")) {
                  exec(hold(RequestNames.HoldRetry))
                }
            }
        }
    }
    .doIf(_.contains("holdId")) {
      exec(_.set("idempotencyKey", UUID.randomUUID().toString))
        .exec(
          http(RequestNames.Payment)
            .post("/api/holds/#{holdId}/payments")
            .header("X-User-Id", "#{userId}")
            .header("Idempotency-Key", "#{idempotencyKey}")
            .header("X-Fake-Pg-Scenario", "SUCCESS")
            .body(StringBody("""{"method": "CARD"}"""))
            .check(status.is(200))
        )
    }

  setUp(
    scn.inject(rampUsers(users).during(rampSeconds.seconds))
  ).protocols(httpProtocol)
}
