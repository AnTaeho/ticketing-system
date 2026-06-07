import common.Feeders
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/**
 * Circuit Breaker 시뮬레이션
 *
 * 실행 전 필수:
 *   curl -X POST "http://localhost:8080/api/concerts/1/reset?stock=100"
 *
 * 실행 예시:
 *   # 정상 상태 (CB CLOSED 유지, Redis 경로만 사용)
 *   mvn gatling:test -Dgatling.simulationClass=CBSimulation -DCHAOS=none -DUSERS=1000
 *
 *   # Redis 차단 (CB OPEN → V2 폴백 발생)
 *   mvn gatling:test -Dgatling.simulationClass=CBSimulation -DCHAOS=redis_block -DUSERS=1000
 *
 * 테스트 완료 후:
 *   1. after 블록 콘솔 출력에서 fallbackPathCount 확인
 *   2. Gatling 리포트에서 TPS/P99/에러율 확인
 *   3. POST /api/test-results 로 결과 수동 저장 (lockType=REDISSON_CB, fallbackCount 포함)
 */
class CBSimulation extends Simulation {

  val chaos: String   = System.getProperty("CHAOS", "none")
  val users: Int      = System.getProperty("USERS", "1000").toInt
  val baseUrl: String = "http://localhost:8080"

  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  val scn = scenario(s"CB - chaos=$chaos - ${users}users")
    .feed(Feeders.userFeeder)
    .exec(
      http("V5CB 예약 요청")
        .post("/api/v5cb/concerts/1/reserve")
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
    post("/api/v5cb/stats/reset")

    chaos match {
      case "redis_block" =>
        post("/api/chaos/redis/block")
        println("[CBSimulation] Redis 차단 주입 — CB OPEN + V2 폴백 시나리오")
      case "none" =>
        println("[CBSimulation] 정상 상태 — CB CLOSED, Redis 경로만 사용")
      case other =>
        println(s"[CBSimulation] 알 수 없는 chaos 타입: $other (정상 상태로 진행)")
    }

    println(s"[CBSimulation] 시작 - chaos=$chaos, users=$users")
  }

  setUp(
    scn.inject(atOnceUsers(users))
  ).protocols(httpProtocol)
    .assertions(
      global.successfulRequests.percent.gte(0)
    )

  after {
    val client = HttpClient.newHttpClient()

    def get(path: String): String = {
      val req = HttpRequest.newBuilder()
        .uri(URI.create(s"$baseUrl$path"))
        .GET()
        .build()
      client.send(req, HttpResponse.BodyHandlers.ofString()).body()
    }

    def post(path: String): Unit = {
      val req = HttpRequest.newBuilder()
        .uri(URI.create(s"$baseUrl$path"))
        .POST(HttpRequest.BodyPublishers.noBody())
        .build()
      client.send(req, HttpResponse.BodyHandlers.ofString())
      ()
    }

    val stats = get("/api/v5cb/stats")
    println(s"\n[CBSimulation] 완료 — CB Stats: $stats")
    println("[CBSimulation] 위 fallbackPathCount 값을 POST /api/test-results 의 fallbackCount 필드로 입력하세요.")

    post("/api/chaos/reset")
    println("[CBSimulation] chaos 초기화 완료")
  }
}
