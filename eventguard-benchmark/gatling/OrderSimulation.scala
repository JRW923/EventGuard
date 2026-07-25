import scala.concurrent.duration._

import io.gatling.core.Predef._
import io.gatling.http.Predef._

/**
 * OrderSimulation —— EventGuard 下单链路压测（对齐计划 M5.4）
 *
 * 场景：下单(POST /orders) → 支付(POST /orders/{id}/pay) → 查询(GET /orders/{id})
 * 递增并发：先 30s 内爬坡到 50 用户，再瞬时追加 20 用户。
 * 断言：全局 P95 延迟 < 500ms（基线，对应 M5.4 验收点），成功率 > 99%。
 *
 * 端点路径以 eventguard-server 实际控制器为准（OrderCommandController / OrderQueryController），
 * 鉴权走 X-API-Key 头（V2 已加）。baseUrl 与 apiKey 可通过环境变量覆盖。
 */
class OrderSimulation extends Simulation {

  // ponytail: 端点路径硬编码对齐 MVP 控制器；若路由变更需同步此处。
  private val baseUrl = sys.env.getOrElse("TARGET_URL", "http://localhost:8080")
  private val apiKey  = sys.env.getOrElse("API_KEY", "changeme")

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .header("Content-Type", "application/json")
    .header("X-API-Key", apiKey)
    .acceptHeader("application/json")

  // 1) 创建订单，从响应 JSON 中取出 orderId 供后续步骤使用
  private val createOrder = http("createOrder")
    .post("/orders")
    .body(StringBody("""{"userId":"load-test","totalAmount":199.0}"""))
    .check(status.is(200))
    .check(jsonPath("$.orderId").saveAs("orderId"))

  // 2) 支付（订单状态机：PENDING_PAYMENT -> PAID）
  private val payOrder = http("payOrder")
    .post("/orders/${orderId}/pay")
    .body(StringBody("""{"paymentId":"pay-${orderId}"}"""))
    .check(status.is(200))

  // 3) 查询订单（读模型投影）
  private val getOrder = http("getOrder")
    .get("/orders/${orderId}")
    .check(status.is(200))

  private val orderLifecycle = scenario("OrderLifecycle")
    .exec(createOrder)
    .exec(payOrder)
    .exec(getOrder)

  setUp(
    orderLifecycle.inject(
      rampUsers(50).during(30.seconds),
      atOnceUsers(20)
    )
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile(95).lt(500),
      global.successfulRequests.percent.gt(99)
    )
}
