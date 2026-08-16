# 部署记录与已知问题（2026-08-09）

> 服务器从远程 main **a09043a** 同步并重建上线时的部署偏差与修复记录。
> 背景：当时 git fetch/SSH/HTTPS 到 github.com 全部超时（143），改用 codeload tarball 全量下载（~15.5MB，
> 不支持断点续传），工作区先对齐到远程 main a09043a（61 文件 / 11 提交）。随后网络恢复，本地 git 历史已
> **重建在 origin/main（31bd030，含其后 3 个新提交 a71c505 重构/08b5eda 文档重组/31bd030 README）之上**，
> 本文记录的回退与修复已一并推送到远程。本次涉及的服务器端偏差与修复如下，留档供后续分析。

---

## 1. alertmanager `--config.expand-env` 回退（与远程 main 不一致，勿覆盖）

### 现象
远程 `docker-compose.yml` 与 `prometheus/alertmanager.yml` 引入环境变量注入：
```yaml
# docker-compose.yml（远程）
command: ["--config.file=/etc/alertmanager/alertmanager.yml", "--config.expand-env"]
environment:
  ALERT_WEBHOOK_URL: ${ALERT_WEBHOOK_URL:-http://127.0.0.1:65534/alert}
```
```yaml
# prometheus/alertmanager.yml（远程）
- url: "${ALERT_WEBHOOK_URL}"
```
按此部署后 alertmanager 进入 crash loop：
```
alertmanager: error: unknown long flag '--config.expand-env', try --help
```

### 根因（已实证）
`--config.expand-env` **只存在于 Prometheus**，Alertmanager 从未支持配置内环境变量展开：
- `docker run --rm prom/alertmanager:v0.27.0 --help`（当前固定镜像，2024-02 构建）无该 flag。
- 拉取 v0.33.0（2026-07 最新）源码 `cmd/alertmanager/main.go` 检查：`expand-env` 字符串不存在。
- Alertmanager 配置内环境变量展开是长期 open 的 feature request（与 Prometheus 不同步）。

即：**远程该改动在其固定镜像版本（乃至全版本线）下都跑不起来**，属上游提交 bug。

### 服务器端处理（回退）
```yaml
# docker-compose.yml（服务器当前）
command: ["--config.file=/etc/alertmanager/alertmanager.yml"]   # 去掉 --config.expand-env
# prometheus/alertmanager.yml（服务器当前）
- url: "http://127.0.0.1:65534/alert"                            # 恢复写死黑洞地址
```
`.env` 中 `ALERT_WEBHOOK_URL` 变量保留但未使用（无副作用）。

### 后续可选方案（供分析）
1. **保持回退**（当前）：告警 webhook 仍写死在配置里，改通知地址需编辑 `prometheus/alertmanager.yml`。
2. **升级 alertmanager** 到支持 env 展开的版本——但经查 **没有任何版本支持**，该路径不可行。
3. **entrypoint 包装**：用 `envsubst` 在容器启动时渲染配置（需新增脚本/镜像），可复现远程意图。
4. **改远程仓库**：把远程 docker-compose/alertmanager.yml 的 env 注入方案回退或改为上述方案，消除双端差异。

---

## 2. R002/R003 假阳性修复（本次已修）

### 2.1 共同根因
`RuleContextLoader` 给规则喂的「历史上下文」把**当前事件自身的效果**也算进去了。AI 桥接端点
（`POST /anomaly/rules/evaluate`）在事件**已落库、投影已更新**后才调用，于是：
- R002 的 `recentPaymentCompletions` 查到了**当前** PaymentCompleted 自身 → 单次支付恒命中「重复支付」。
- R003 的 `previousState` 拿到的是**当前事件应用后**的 order_view 状态（如 PAID）→ 合法迁移
  `PENDING_PAYMENT→PAID` 也报「状态跳跃」。

均**预先存在**（规则与查询在 `3e06d4f..a09043a` 之间无改动，RuleContextLoader 仅 std 重构），非本次 11 提交引入。

### 2.2 R002 修复
`load` 把 `event.getEventId()` 传给 `loadRecentPaymentCompletions`，SQL 加 `AND event_id<>?` 排除当前事件；
`recent` 现在只含**真正之前**的支付完成。真重复支付（存在第二条 PaymentCompleted）仍命中。

### 2.3 R003 修复
`load` 把 `event.getVersion()` 传给 `loadPreviousState`，SQL 改为
`SELECT status FROM order_view WHERE order_id=? AND version < ? ORDER BY version DESC LIMIT 1`——
取当前事件**应用前**的聚合状态。`order_view` 有 `version` 列（V2 建表），版本过滤后：评估 PaymentCompleted(version=3)
取 version<3 → PENDING_PAYMENT ∈ 合法集 → 不报；评估 ShippedEvent(前序非 CONFIRMED) → 报。

### 2.4 验证
- `mvn package`：**Tests run: 164, Failures: 0, Errors: 0, Skipped: 4**。
- 线上复测：
  - 正常订单创建→支付一次：`/alerts/recent` **无 R002、无 R003**。
  - 构造非法迁移（未支付订单 version1 上桥接评估 ShippedEvent version2）：**命中 R003**。
  - 合法迁移（version1 上 PaymentCompleted）：未命中。

### 2.5 同类排查
- R004 高频下单：`recentCreateOrders` 含当前事件是**正确**的（频率计数本就该包含本次）。
- R001 金额偏离：均值含当前事件会稀释偏离（单笔订单 mean=自身 → 不触发），偏保守非假阳性，属 MVP 取舍。
- R005 库存越界：读库存网关实时库存，无自包含问题。

---

## 3. 其他部署差异速查

- **eventguard-server/Dockerfile**：`mvn package`（去掉 `-DskipTests`）——构建时跑测试，164 通过/4 跳过
  （2 个 testcontainers 集成测试有 `@EnabledIfSystemProperty(eventguard.run.integration=true)` + 
  `disabledWithoutDocker`，构建环境自动跳过）。
- **application.yml**：`management.endpoints.web.exposure.include` 从 `health,info,metrics,prometheus`
  收敛为 `health,prometheus`；V6 迁移纳入 `spring.sql.init.schema-locations`（`IF NOT EXISTS` 幂等）。
- **.env**：新增 `EG_ENV=demo` 等环境保护开关与 `ALERT_WEBHOOK_URL`（demo 模式，ProductionSecurityGuard 不强制校验）。
- **git**：历史已重建在 origin/main（31bd030）之上，alertmanager 回退 + R002/R003 修复 + 本文档已推送。
  ⚠️ 服务器上运行的容器仍基于 a09043a 内容构建（未含 a71c505 重构等 3 个新提交），下次 `--build` 会一并带上。
