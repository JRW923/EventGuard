# EventGuard Gatling 压测（M5.4）

对订单核心链路做递增并发压测，产出 QPS 与 P95 延迟报告。场景对齐计划 M5.4：
**下单 → 支付 → 查询**，断言全局 P95 < 500ms、成功率 > 99%。

## 文件

- `OrderSimulation.scala` —— Gatling 3.x HTTP 仿真（下单/支付/查询三连，递增并发）。
- `build.sbt` —— 最小 sbt Gatling 工程（可选）。

## 前置

- 已 `docker compose up -d --build` 且全栈健康（压测打的是真实运行中的 `eventguard-server:8080`）。
- 目标端点需鉴权：脚本默认带 `X-API-Key: changeme`（与 README 默认密钥一致）。
  若改过密钥，运行前设置环境变量 `API_KEY`。

> ponytail: 本机无 sbt / 无运行中的全栈，无法编译或产出真实报告；
> 以下为运行方式说明，实跑需在具备 sbt 或官方 Gatling 发行版 + 运行全栈的环境执行。

## 运行方式一：官方 Gatling 发行版（无需 sbt）

1. 下载并解压 [Gatling](https://gatling.io/) 发行版。
2. 把 `OrderSimulation.scala` 放到 `user-files/simulations/` 下。
3. 运行（默认 `TARGET_URL=http://localhost:8080`）：

   ```bash
   TARGET_URL=http://localhost:8080 API_KEY=changeme \
     ./bin/gatling.sh -s class OrderSimulation
   ```

## 运行方式二：sbt Gatling 插件

```bash
# 在工程根（含 build.sbt）执行
API_KEY=changeme sbt "gatling:testOnly OrderSimulation"
```

## 预期产出

- 控制台实时输出：请求数、RPS（QPS）、各百分位延迟。
- HTML 报告：`results/` 目录下按时间戳生成的报告（含 QPS 曲线、P95 延迟分布）。
- 断言：P95 < 500ms 且成功率 > 99% 时退出码为 0；否则非 0（CI 可直接用）。

## 调参

- 并发档位在 `OrderSimulation.scala` 的 `inject(...)` 中：`rampUsers(50).during(30.seconds)`
  表示 30s 内爬坡到 50 用户，`atOnceUsers(20)` 瞬时追加 20。按需改大做更高压力测试。
- 跑更长时间：在 `inject` 中改用 `constantUsersPerSec(n).during(5.minutes)` 做稳态压测。
