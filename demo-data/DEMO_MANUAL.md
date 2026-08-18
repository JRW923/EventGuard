# EventGuard 演示手册

> 适用版本：本仓库当前 `main`。演示数据已通过 `demo-data/` 下的脚本一键保存/恢复。
> 演示账号（均为演示专用，密码固定）：
> - `admin / admin123456`（管理员，可审批、可配置 LLM）
> - `operator / operator123456`（运营，可审批、可看异常）
> - `viewer / viewer123456`（只读，不能审批）
> UI 访问地址：`https://eventguard.jrwdev.site`（前端 nginx 反代；后端 API 在 `localhost:8080`，AI 在 `localhost:8000`）。

---

## 0. 一键恢复演示数据（每次演示前必做）

演示数据已固化在 `demo-data/` 快照中。无论你上一轮演示把它改成了什么样（新建订单、审批通过/驳回、生成新告警），一键即可还原到干净状态：

```bash
bash demo-data/restore_demo.sh
```

脚本会：停消费者 → 恢复数据库 → 恢复 AI 异常存储 → 恢复校准后的 IF 模型 → **把 4 个 Kafka 消费组 offset 重置到 latest**（跳过恢复数据库时产生的 CDC，避免重复告警/重复触发 Saga）→ 重启服务。

还原后预期：
- 订单中心共 **51 单**（PAID 11 / CONFIRMED 4 / SHIPPED 4 / DELIVERED 3 / CLOSED 3 / CANCELLED 3 / REFUNDED 2）。
- 异常看板 **6 条**告警：R001×1、R004×2、R005×1、P001（非法状态转移）×2。
- 审批队列 **1 条 PENDING**（u-approve-1 的 REFUND 320）。

> 若改动了演示数据想重新固化为新基线：`bash demo-data/save_demo.sh`（覆盖快照）。

---

## 1. 环境启动 / 停止

```bash
# 全量启动（含 postgres / kafka / debezium / server / ai / ui）
docker compose up -d
# 仅看健康状况
curl -s http://localhost:8080/health      # server
curl -s http://localhost:8000/health      # ai（detector.running 应为 true）
```

> 限流默认开启（`EG_RATE_LIMIT_ENABLED=true`）。生成演示数据脚本内部会临时关闭限流（`EG_RATE_LIMIT_ENABLED=false docker compose up -d eventguard-server`），跑完务必恢复默认并重启 server。

---

## 2. 三个核心页面走查

登录后默认进入「订单中心」。左侧菜单切换三个演示重点页。

### 2.1 订单中心（OrderList）
- 列表展示订单状态分布；顶部「订单统计」按状态聚合金额。
- **演示点**：状态覆盖完整生命周期（已取消 / 已支付 / 已确认 / 已发货 / 已送达 / 已关闭 / 已退款），体现事件溯源 + CQRS 读模型。
- 行内「预测」按钮调用 AI 订单终局预测（见 §4.6，无需 LLM）。
- 点订单可看**事件流**（Event Sourcing 的逐事件回溯）。

### 2.2 异常看板（AnomalyDashboard）
- 顶部开关切换「明细模式 / 聚合模式」。
- 明细模式：逐条告警（规则 ID、级别、描述、检测时间、「查看根因」）。
- 聚合模式：按 (规则, 订单) 聚类，展示命中次数与时间跨度。
- **演示点**：R001（金额偏离 9600）、R004（21 单/分钟 高频）、R005（库存溢出）三条规则告警，加 P001（库存失败导致的非法状态转移）。WebSocket 实时推送新告警。
- 「查看根因」需先配置 LLM（见 §4.1）。

### 2.3 审批队列（Approvals）
- 列出 `PENDING` 的补偿审批单。`operator` / `admin` 可「通过 / 驳回」；`viewer` 访问返回 403（正确，只读账号无权限）。
- **演示点**：u-approve-1 的 REFUND 320（金额 > 100 触发审批）正等待处理。通过后会启动补偿 Saga 并改写订单状态。

---

## 3. AI 功能演示总览

| 功能 | 入口页面 | 是否需 LLM Key |
|------|---------|---------------|
| 订单终局预测 | 订单中心 → 行内「预测」 | ❌ 不需（ML 模型） |
| 自然语言查询 | NLQuery 页 | ⚠️ 可选（未配时降级为关键词+数据摘要，仍可用） |
| 根因分析 | 异常看板 → 某告警「查看根因」 | ✅ 需 |
| 深度分析（Agent） | 同上弹窗 → 「深度分析（Agent）」 | ✅ 需 |
| 运营周报 | AiReport 页 → 「生成周报」 | ✅ 需 |
| 订单故事 | AiReport 页 → 输入订单 ID → 「订单故事」 | ✅ 需 |

---

## 4. AI 功能逐步演示

### 4.1 配置你的 LLM Key（一次性）
1. 用 `admin` 或 `operator` 登录 → 右上角头像 → **个人中心**。
2. 找到「**我的 LLM 配置**」卡片（`LlmSettings.vue`）。
3. 填写：
   - Provider：`openai` 或 `anthropic`
   - Base URL：兼容端点（如 `https://api.openai.com/v1`）
   - Model：如 `gpt-4o-mini`
   - API Key：保存后后端 AES 加密，列表只回掩码
   - maxTokens（默认 2048）、temperature（默认 0.3）
4. 点「保存」。当前 `hasApiKey` 变为 `true`。

> Key 按用户存储于 Java 侧 PostgreSQL，对外只给掩码。换账号演示需各自配置。

### 4.2 根因分析（Root Cause）
- 异常看板 → 点 **R001 / R004 / R005** 任一条 → 「查看根因」。
- 返回 `root_cause` / `evidence` / `suggestions`（处置动作 + 风险 + 金额）。
- 需 LLM；未配置时接口返回 409「请先在个人中心配置你的 LLM API」。

### 4.3 深度分析（ReAct Agent）
- 同弹窗 → 「深度分析（Agent）」。Agent 多轮调用工具收集证据，返回报告 + `agent_trace`（可展开看每一步工具输入输出）。
- 超时较长（最长 ~5 步工具调用），前端已放宽到 30s。

### 4.4 运营周报（Weekly Report）
- AiReport 页 → 选天数（默认 7）→ 「生成周报」。聚合近期异常 + 订单统计 + LLM 生成的症状/建议文案。

### 4.5 订单故事（Order Story）
- AiReport 页 → 输入任意订单 ID（如某 `PAID` 订单）→ 「订单故事」。把事件链渲染成可读的运营复盘。

### 4.6 订单终局预测（Predict，无需 LLM）
- 订单中心 → 某订单行「预测」按钮（或 `GET /ai/predict/{orderId}`）。
- 走 ML 模型（`predictor.pkl`），返回 `outcome` / `confidence` / `risk`。
- **已实测**：对 `PAID` 订单返回 `outcome=CLOSED, confidence≈0.93, risk=LOW`。此功能开箱即用，可作为"AI 链路已通"的快速证明。

### 4.7 自然语言查询（NL Query，LLM 可选）
- NLQuery 页 → 输入问题，如"最近有哪些高风险订单？""上周异常了多少单？"。
- 未配置 LLM 时降级为意图分类 + 数据摘要，仍给出答案；配置后由 LLM 润色。

---

## 5. 演示数据构成（快照内容）

| 类别 | 内容 |
|------|------|
| 健康订单 | 24 单覆盖各生命周期状态，均唯一用户 |
| R005 库存溢出 | u-r005 订单 300，预留 SKU-A 150（库存 100）触发 `InventoryReservationFailedEvent` |
| R004 高频 | u-r004 连续 21 单（金额恒定 100，间隔 0.5s）触发高频规则 |
| R001 金额偏离 | u-r001 基线 188/213/199，待 35s 缓存过期后打 9600 离群单 |
| 待审批补偿 | u-approve-1 订单 320 → startSaga(REFUND 320，金额>100) 生成 PENDING 审批单 |

> 设计要点：健康订单用**唯一用户**以避免 R001 误报；R004 用**恒定金额**避免突发期间 R001 误报；R001 离群点等 35s 用户统计缓存过期后再打，确保命中。

---

## 6. 本演示相关的已修复问题

### 6.1 限流误拦规则引擎端点（RateLimitFilter）
- 症状：机器认证的规则引擎端点 `/anomaly/rules/evaluate`（AI 检测器 `RuleBridge` 单线程高频调用）被按 IP 的固定窗口限流返回 429，导致 R001–R005 检测被**静默跳过**（检测器"假死"降级）。
- 修复：`/anomaly/rules/**` 走机器密钥认证，不再进入普通用户限流桶。

### 6.2 R001 永不命中（RuleContextLoader）
- 症状：R001（金额偏离）在生产环境数学上不可能触发——计算用户金额均值/标准差时把**正在评估的当前事件自身**也纳入基线，离群值被自身基线稀释。
- 修复：加载用户金额统计时排除当前事件（`event_id <> ?`）。

### 6.3 Isolation Forest 在演示数据上误报（IF 校准）⭐
- 症状：异常看板原本 54 条告警里 **48 条是 IF 低优先级误报**（51 单里近 48 单被标异常）。
- 根因（训练/服务特征偏移）：演示事件**只有 `OrderCreatedEvent` 携带 `userId`**，其余事件 `metadata.userId` 为 NULL；特征提取器按 `userId` 建基线，于是大量事件退化成离群特征 `[0,*,0,1.0]`，与 45MB 训练集（每事件都有 userId、特征有方差）分布严重偏移。
- 修复（方案1，已持久化）：
  1. `EventLevelDetector` 支持 `EG_IF_MODEL_PATH` / `EG_IF_SCALER_PATH` 可配置路径。
  2. 用「演示正常事件（排除 3 个异常用户）+ 45MB 真实正常数据抽样」混合重训 Isolation Forest，模型落到挂载目录 **`ai-data/models/`**（随卷持久化，**重建镜像即生效，无需改镜像**）。
  3. `docker-compose.yml` 注入 `EG_IF_MODEL_PATH=/data/models/isolation_forest.pkl`。
- 效果（已验证）：正常订单 **0 误报**；真实异常仍被 IF 命中——u-r004 突发 20/21、u-r001 金额尖峰 3/4。标准化器有非零方差，推理稳定。

---

## 7. 常见问题

- **Q：看板出现大量 `IF` 告警？** 不会了（已校准）。若你自行重训或换模型，确保新模型放在 `ai-data/models/` 且 `EG_IF_MODEL_PATH` 指向它。
- **Q：根因分析返回 409？** 当前登录用户未在「个人中心」配置 LLM Key。
- **Q：审批按钮点不了？** 用 `viewer` 登录无权限；换成 `operator` / `admin`。
- **Q：生成新数据后看板变脏？** 跑 `bash demo-data/restore_demo.sh` 一键还原。
- **Q：预测功能报错？** 检查 `models/predictor.pkl` 是否存在（AI 容器 `/app/models` 或挂载目录）；预测不依赖 LLM。
- **Q：限流导致批量生成被 429？** 生成脚本会临时关限流；跑完确认 `EG_RATE_LIMIT_ENABLED=true` 并已重启 server。

---

## 8. 一键脚本速查

| 脚本 | 作用 |
|------|------|
| `demo-data/restore_demo.sh` | 一键恢复干净演示数据（每次演示前） |
| `demo-data/save_demo.sh` | 把当前数据重新固化为新基线（覆盖快照） |
| `demo-data/reset_offsets.py` | 内部工具：将 4 个 Kafka 消费组 offset 重置到 latest（恢复时自动调用） |
