import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

/**
 * 사용자 한 명의 흐름을 CSV 한 줄로 남긴다. simulation.log에는 사용자 구분과 응답 본문이 없어서
 * 입장 대기 시간, 결제 200·202 구분, 포기 이유는 여기서만 셀 수 있다. 경로(-DUSER_LOG)가 없으면 남기지 않는다
 */
class UserLog(path: Option[String]) {

  private val rows = new ConcurrentLinkedQueue[String]()

  val record: ChainBuilder = exec { s =>
    if (path.isDefined) rows.add(row(s))
    s
  }

  def write(): Unit = path.foreach { p =>
    val header = "n,enter_at_ms,admitted_at_ms,polls,hold_attempts,hold_status,pay_status,outcome," +
      "heartbeats,heartbeat_status,leave_status,sold_out_at_ms"
    Files.write(Paths.get(p), (header +: rows.asScala.toSeq).mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8))
  }

  private def row(s: Session): String = {
    def opt(key: String): String = s(key).asOption[Any].map(_.toString).getOrElse("")
    Seq(opt("n"), opt("enterAt"), opt("admittedAt"), s("polls").asOption[Int].getOrElse(0).toString,
      s("holdAttempts").asOption[Int].getOrElse(0).toString, opt("holdStatus"), opt("payStatus"), outcome(s),
      s("heartbeats").asOption[Int].getOrElse(0).toString, opt("heartbeatStatus"), opt("leaveStatus"),
      opt("soldOutAt"))
      .mkString(",")
  }

  private def outcome(s: Session): String =
    if (!s.contains("userId")) "login_failed"
    else if (!s.contains("queueToken"))
      if (s("queueStatus").asOption[String].contains("SOLD_OUT")) "enter_sold_out" else "queue_enter_failed"
    else if (!s.contains("admittedAt")) s("queueStatus").asOption[String] match {
      case Some("WAITING") => "queue_timeout"
      case Some(other)     => s"queue_${other.toLowerCase}"
      case None            => "queue_unknown"
    }
    else if (s.contains("payStatus")) s"pay_${s("payStatus").as[Int]}"
    else if (s.contains("holdId")) "pay_error"
    else if (s.contains("soldOut")) "soldout"
    else s("holdStatus").asOption[Int] match {
      case Some(409)  => "gave_up_409"
      case Some(code) => s"hold_$code"
      case None       => if (s("holdAttempts").asOption[Int].getOrElse(0) > 0) "no_seats" else "no_hold"
    }
}
