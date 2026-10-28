import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties

/** 요청 이름은 request-names.properties 한 곳에서 정한다. 집계 스크립트도 같은 파일을 읽는다 */
object RequestNames {

  private val names: Properties = {
    val in = Option(getClass.getResourceAsStream("/request-names.properties"))
      .getOrElse(throw new IllegalStateException("클래스패스에 request-names.properties가 없다"))
    val props = new Properties()
    try props.load(new InputStreamReader(in, StandardCharsets.UTF_8))
    finally in.close()
    props
  }

  private def name(key: String): String =
    Option(names.getProperty(key))
      .getOrElse(throw new IllegalStateException(s"request-names.properties에 $key 가 없다"))

  val Login: String        = name("login")
  val QueueEnter: String   = name("queue_enter")
  val QueuePoll: String    = name("queue_poll")
  val SeatMap: String      = name("seatmap")
  val Hold: String         = name("hold")
  val SeatMapRetry: String = name("seatmap_retry")
  val HoldRetry: String    = name("hold_retry")
  val Payment: String      = name("payment")
  val Heartbeat: String    = name("heartbeat")
  val Leave: String        = name("leave")
}
