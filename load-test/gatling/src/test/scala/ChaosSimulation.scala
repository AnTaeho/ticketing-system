import common.Feeders
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/**
 * Chaos 시뮬레이션 — 장애 주입 후 부하 테스트
 *
 * 실행 전 필수:
 *   curl -X POST "http://localhost:8080/api/concerts/1/reset?stock=100"
 *
 * 실행 예시:
 *   # V2, HikariCP pool=5 제한, 동시 500명
 *   mvn gatling:test -Dgatling.simulationClass=ChaosSimulation -DVERSION=v2 -DCHAOS=hikari -DCHAOS_PARAM=5 -DUSERS=500
 *
 *   # V4, Redis 500ms 지연, 동시 500명
 *   mvn gatling:test -Dgatling.simulationClass=ChaosSimulation -DVERSION=v4 -DCHAOS=redis_delay -DCHAOS_PARAM=500 -DUSERS=500
 *
 *   # V5, Redis 완전 차단, 동시 500명
 *   mvn gatling:test -Dgatling.simulationClass=ChaosSimulation -DVERSION=v5 -DCHAOS=redis_block -DCHAOS_PARAM=0 -DUSERS=500
 *
 *   # V6, Kafka 컨슈머 중지, 동시 500명
 *   mvn gatling:test -Dgatling.simulationClass=ChaosSimulation -DVERSION=v6 -DCHAOS=kafka -DCHAOS_PARAM=0 -DUSERS=500
 */
class ChaosSimulation extends Simulation {

  val version: String  = System.getProperty("VERSION", "v2")
  val chaos: String    = System.getProperty("CHAOS", "hikari")
  val chaosParam: Int  = System.getProperty("CHAOS_PARAM", "5").toInt
  val users: Int       = System.getProperty("USERS", "500").toInt
  val baseUrl: String  = "http://localhost:8080"

  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  val scn = scenario(s"Chaos - $version - $chaos($chaosParam) - ${users}users")
    .feed(Feeders.userFeeder)
    .exec(
      http("예약 요청 (장애 주입 중)")
        .post(s"/api/$version/concerts/1/reserve")
        .body(StringBody("""{"userId": #{userId}}"""))
        .check(status.in(200, 409, 500))
    )

  before {
    val client = HttpClient.newHttpClient()

    def post(path: String): Unit = {
      val req = HttpRequest.newBuilder()
        .uri(URI.create(s"$baseUrl$path"))
        .POST(HttpRequest.BodyPublishers.noBody())
        .build()
      client.send(req, HttpResponse.BodyHandlers.ofString())
      ()
    }

    post("/api/chaos/reset")

    chaos match {
      case "hikari"      => post(s"/api/chaos/hikari/constrain?maxPoolSize=$chaosParam")
      case "redis_delay" => post(s"/api/chaos/redis/delay?ms=$chaosParam")
      case "redis_block" => post("/api/chaos/redis/block")
      case "kafka"       => post("/api/chaos/kafka/pause")
      case other         => println(s"[ChaosSimulation] 알 수 없는 chaos 타입: $other")
    }

    println(s"[ChaosSimulation] 장애 주입 완료 - version=$version, chaos=$chaos, param=$chaosParam, users=$users")
  }

  setUp(
    scn.inject(atOnceUsers(users))
  ).protocols(httpProtocol)
    .assertions(
      global.successfulRequests.percent.gte(0)
    )

  after {
    val client = HttpClient.newHttpClient()
    val req = HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl/api/chaos/reset"))
      .POST(HttpRequest.BodyPublishers.noBody())
      .build()
    client.send(req, HttpResponse.BodyHandlers.ofString())
    println("[ChaosSimulation] chaos 전체 초기화 완료")
  }
}
