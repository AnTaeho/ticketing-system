import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._
import io.gatling.http.protocol.HttpProtocolBuilder

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.util.Random

/** 시나리오들이 함께 쓰는 예매 단계(로그인, 대기열, 좌석맵, 잡기, 결제). 요청 이름은 RequestNames에서 읽는다 */
object BookingSteps {

  // 좌석맵의 좌석 상태 한 글자(A/H/S) 중 빈자리. 구역 이름 A와는 상관없다
  private val SeatAvailable = "A"

  // 좌석맵은 구역·열 순서로 오므로 첫 구역의 첫 열이 인기석(A구역 1열)이다
  private val HotRowSeats = "$.sections[0].rows[0].seats[*]"

  def intProp(name: String, default: Int): Int = sys.props.get(name).map(_.toInt).getOrElse(default)

  def performanceId: Long = sys.props.get("PERFORMANCE_ID").map(_.toLong)
    .getOrElse(throw new IllegalArgumentException("-DPERFORMANCE_ID가 필요하다"))

  // BASE_URLS에 주소를 쉼표로 여럿 주면 Gatling이 사용자마다 번갈아 쓴다
  def protocol: HttpProtocolBuilder = {
    val urls = sys.props.getOrElse("BASE_URLS", sys.props.getOrElse("BASE_URL", "http://localhost:8080"))
      .split(",").map(_.trim).filter(_.nonEmpty).toList
    http
      .baseUrls(urls)
      .acceptHeader("application/json")
      .contentTypeHeader("application/json")
  }

  // 닉네임에 회차 ID를 넣어 회차마다 새 사용자로 시작한다. 10명 중 7명이 인기석을 노린다
  def userFeeder(performanceId: Long): Iterator[Map[String, Any]] = {
    val sequence = new AtomicInteger()
    Iterator.continually {
      val n = sequence.incrementAndGet()
      Map("n" -> n, "nickname" -> s"lt$performanceId-$n", "hot" -> (n % 10 < 7))
    }
  }

  def perfPath(performanceId: Long): String = s"/api/performances/$performanceId"

  val login: ChainBuilder = exec(
    http(RequestNames.Login)
      .post("/api/demo/login")
      .body(StringBody("""{"nickname": "#{nickname}"}"""))
      .check(status.is(200))
      .check(jsonPath("$.userId").ofType[Long].saveAs("userId"))
  )

  def enterQueue(perf: String): ChainBuilder =
    exec(_.set("enterAt", System.currentTimeMillis()))
      .exec(
        http(RequestNames.QueueEnter)
          .post(s"$perf/queue/tokens")
          .header("X-User-Id", "#{userId}")
          .check(status.is(200))
          .check(jsonPath("$.token").saveAs("queueToken"))
          .check(jsonPath("$.status").saveAs("queueStatus"))
      )

  private def waiting(s: Session): Boolean = s("queueStatus").asOption[String].contains("WAITING")

  def admitted(s: Session): Boolean = s.contains("admittedAt")

  // PROCESSING을 본 시각을 admittedAt으로 남긴다. maxWait가 지나도 WAITING이면 포기한다
  def waitForAdmission(perf: String, pauseMin: FiniteDuration, pauseMax: FiniteDuration,
                       maxWait: FiniteDuration): ChainBuilder =
    asLongAs(s => waiting(s) && System.currentTimeMillis() - s("enterAt").as[Long] < maxWait.toMillis) {
      pause(pauseMin, pauseMax)
        .exec(
          http(RequestNames.QueuePoll)
            .get(s"$perf/queue/tokens/#{queueToken}")
            .header("X-User-Id", "#{userId}")
            .check(status.is(200))
            .check(jsonPath("$.status").saveAs("queueStatus"))
        )
        .exec(s => s.set("polls", s("polls").asOption[Int].getOrElse(0) + 1))
    }
      .doIf(s => s("queueStatus").asOption[String].contains("PROCESSING")) {
        exec(_.set("admittedAt", System.currentTimeMillis()))
      }

  private def seatMap(perf: String, name: String) =
    http(name)
      .get(s"$perf/seats")
      .header("X-User-Id", "#{userId}")
      .check(status.is(200))
      .check(jsonPath(s"$HotRowSeats.id").ofType[Long].findAll.saveAs("hotIds"))
      .check(jsonPath(s"$HotRowSeats.s").findAll.saveAs("hotStates"))
      .check(jsonPath(s"$$..seats[?(@.s == '$SeatAvailable')].id").ofType[Long].findAll.optional.saveAs("freeIds"))

  // 상태 코드를 먼저 남겨야 201이 아닐 때도 출처를 셀 수 있다
  private def hold(perf: String, name: String): ChainBuilder =
    exec(
      http(name)
        .post(s"$perf/holds")
        .header("X-User-Id", "#{userId}")
        .body(StringBody("""{"seatIds": #{seatIds}, "queueToken": "#{queueToken}"}"""))
        .check(status.saveAs("holdStatus"))
        .check(status.is(201))
        .check(jsonPath("$.holdId").ofType[Long].optional.saveAs("holdId"))
    ).exec(s => s.set("holdAttempts", s("holdAttempts").asOption[Int].getOrElse(0) + 1))

  // 재시도에서는 인기석 사용자도 빈 연속 2석이 없으면 아무 빈자리 2석으로 물러난다.
  // stopWhenSoldOut이면 빈자리가 2석 미만일 때 soldOut을 남기고 고르지 않는다
  private def chooseSeats(retry: Boolean, stopWhenSoldOut: Boolean)(session: Session): Session = {
    val cleared = session.remove("seatIds")
    session("hotIds").asOption[Seq[Long]] match {
      case None => cleared
      case Some(hotIds) =>
        val hotStates = session("hotStates").as[Seq[String]]
        val freeIds   = session("freeIds").asOption[Seq[Long]].getOrElse(Seq.empty)
        if (stopWhenSoldOut && freeIds.size < 2) {
          cleared.set("soldOut", true)
        } else {
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

          picked.fold(cleared)(ids => cleared.set("seatIds", ids.mkString("[", ",", "]")))
        }
    }
  }

  private def shouldRetry(s: Session, maxRetries: Int): Boolean =
    !s.contains("holdId") && !s.contains("soldOut") &&
      s("holdStatus").asOption[Int].contains(409) && s("retries").asOption[Int].getOrElse(0) < maxRetries

  // 첫 잡기가 409면 좌석맵을 다시 받아 maxRetries번까지 다시 잡는다. 409가 아닌 실패는 다시 하지 않는다
  def seatAttempts(perf: String, maxRetries: Int, stopWhenSoldOut: Boolean): ChainBuilder =
    exec(seatMap(perf, RequestNames.SeatMap))
      .exec(chooseSeats(retry = false, stopWhenSoldOut) _)
      .doIf(_.contains("seatIds")) {
        hold(perf, RequestNames.Hold)
      }
      .asLongAs(s => shouldRetry(s, maxRetries)) {
        exec(s => s.removeAll("hotIds", "hotStates", "freeIds", "holdStatus")
          .set("retries", s("retries").asOption[Int].getOrElse(0) + 1))
          .exec(seatMap(perf, RequestNames.SeatMapRetry))
          .exec(chooseSeats(retry = true, stopWhenSoldOut) _)
          .doIf(_.contains("seatIds")) {
            hold(perf, RequestNames.HoldRetry)
          }
      }

  // 202(결과 대기)도 정상 흐름이다. 200과 202는 사용자 기록의 payStatus로 가른다
  val pay: ChainBuilder = doIf(_.contains("holdId")) {
    exec(_.set("idempotencyKey", UUID.randomUUID().toString))
      .exec(
        http(RequestNames.Payment)
          .post("/api/holds/#{holdId}/payments")
          .header("X-User-Id", "#{userId}")
          .header("Idempotency-Key", "#{idempotencyKey}")
          .body(StringBody("""{"method": "CARD"}"""))
          .check(status.saveAs("payStatus"))
          .check(status.in(200, 202))
      )
  }

  // 로그인 → 대기열 → 입장 대기 → 잡기 → 결제. 어디서 멈췄든 마지막에 사용자 기록을 남긴다
  def booking(perf: String, pauseMin: FiniteDuration, pauseMax: FiniteDuration, maxWait: FiniteDuration,
              maxRetries: Int, stopWhenSoldOut: Boolean, userLog: UserLog): ChainBuilder =
    exec(login)
      .doIf(_.contains("userId")) {
        exec(enterQueue(perf))
          .doIf(_.contains("queueToken")) {
            exec(_.remove("admittedAt"))
              .doIfOrElse(s => s("queueStatus").asOption[String].contains("PROCESSING")) {
                exec(_.set("admittedAt", System.currentTimeMillis()))
              } {
                waitForAdmission(perf, pauseMin, pauseMax, maxWait)
              }
          }
          .doIf(admitted _) {
            exec(seatAttempts(perf, maxRetries, stopWhenSoldOut)).exec(pay)
          }
      }
      .exec(userLog.record)
}
