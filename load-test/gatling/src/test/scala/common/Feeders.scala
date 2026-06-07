package common

import scala.util.Random

object Feeders {

  /** 매 요청마다 다른 userId를 사용해 동일 사용자 중복 예약을 방지 */
  val userFeeder: Iterator[Map[String, Any]] =
    Iterator.continually(Map("userId" -> (Random.nextInt(1000000) + 1)))

}
