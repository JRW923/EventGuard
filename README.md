# EventGuard

电商订单的**事件溯源（Event Sourcing）+ AI 异常检测 + 自然语言查询**管理台。
一条订单从创建到关闭的每一步都作为不可变事件写入事件库，由查询侧投影出可读视图；
Python AI 服务在事件流上做异常检测（规则 + IsolationForest + LLM 根因），
并提供用中文提问、自动执行查询的 NL 接口；Vue3 管理台聚合订单列表、异常看板、
NL 查询、事件时间线与补偿执行。

> 技术栈：PostgreSQL + Debezium CDC → Kafka → Spring Boot（命令侧 / 查询侧）+ Python（FastAPI，AI）→ Vue3（管理前端），全部由 `docker-compose` 编排。

## 架构（文字版）

```
                ┌──────────── 写命令 ────────────┐
   Vue3 管理台  │  Spring Boot 命令侧            │  事件溯源写入
   (localhost:3000) ── REST ──▶ OrderCommandController ──▶ EventStore(Postgres)
        │  │                                                          │ CDC
        │  │  读查询                                                  ▼
        │  └──▶ OrderQueryController ──▶ order_view 投影 ◀── Debezium ─┘
        │                                                              │ Kafka
        │                                               异常检测 ◀─────┘
        │                                          ┌──────────────────────────┐
        └── NL 查询 / 根因 ──▶ FastAPI AI(8000) ──▶│ 规则 + IsolationForest   │
                                                  │ + LLM 根因(可降级)        │
                                                  └──────────────────────────┘
   异常告警经 WebSocket(ws://host/ws/anomalies) 推送给前端异常看板
```

- **PostgreSQL + Debezium**：开启逻辑复制，订单事件表变更经 CDC 流入 Kafka（`pgdata` 持久化于卷）。
- **Kafka**：事件总线；AI 服务消费事件流做实时检测。
- **Spring Boot（命令侧）**：接收写命令、追加事件（`EventStore.append`，版本续接），保证溯源一致性。
- **Spring Boot（查询侧）**：消费事件投影出 `order_view`，提供列表/时间线/统计读接口。
- **Python AI 服务（FastAPI）**：异常检测（规则引擎 + 无监督 IsolationForest + LLM 根因）；NL 查询（意图分类 + 模板执行后端接口）。无 Ollama 时 LLM 走兜底，不阻断演示。
- **Vue3 管理前端**：订单列表、异常看板（WebSocket）、NL 查询、事件时间线、补偿执行。

## 一键部署

```bash
cp .env.example .env          # .env 已被 gitignore，请勿提交
docker compose up -d --build
```

启动后访问 **http://localhost:3000**。

服务端口：UI `3000`、Java `8080`、AI `8000`、Postgres `5432`、Kafka `9092`。
可选混沌测试：`docker compose --profile chaos up -d`（pumba 每 60s 随机杀容器，验证韧性）。

## 各模块测试

| 模块 | 命令 | 说明 |
| --- | --- | --- |
| `eventguard-ai` | `cd eventguard-ai && python -m pytest` | AI 检测 / NL 查询单测 |
| `eventguard-server` | `cd eventguard-server && mvn test` | 2 个 Testcontainers 集成测试类默认跳过（需本地 Docker 资源） |
| `eventguard-ui` | `cd eventguard-ui && npm run test` | Vue 组件 / 视图单测（vitest） |

## 演示脚本（最短路径）

> 需先 `docker compose up -d --build` 且服务健康。下面用 curl 跑通「建订单 → 驱动生命周期 → NL 查询 → 看异常看板 → 触发补偿」。

```bash
# 1) 建订单，拿到 orderId
OID=$(curl -s -X POST http://localhost:8080/orders -H "Content-Type: application/json" \
  -d '{"userId":"u-demo","totalAmount":199.0}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['orderId'])")
echo "orderId=$OID"

# 2) 驱动生命周期：支付 → 发货 → 送达 → 关闭
curl -s -X POST http://localhost:8080/orders/$OID/pay   -H "Content-Type: application/json" -d '{"paymentId":"p1"}' >/dev/null
curl -s -X POST http://localhost:8080/orders/$OID/ship  >/dev/null
curl -s -X POST http://localhost:8080/orders/$OID/deliver >/dev/null
curl -s -X POST http://localhost:8080/orders/$OID/close >/dev/null

# 3) NL 查询（三类意图：订单查询 / 统计聚合 / 轨迹回放）
curl -s -X POST http://localhost:8000/ai/query -H "Content-Type: application/json" \
  -d "{\"question\":\"订单 $OID 当前状态是什么\"}" ; echo
curl -s -X POST http://localhost:8000/ai/query -H "Content-Type: application/json" \
  -d '{"question":"最近7天有多少订单"}' ; echo
curl -s -X POST http://localhost:8000/ai/query -H "Content-Type: application/json" \
  -d "{\"question\":\"订单 $OID 经历了哪些状态变更\"}" ; echo

# 4) 看异常看板
#    - 先看板需要异常数据：向 Kafka 发合成异常序列（含 normal + anomaly）
cd eventguard-ai && python training/generate_data.py && cd ..
#    - 浏览器打开 http://localhost:3000 的「异常看板」，经 WebSocket 实时收到告警；
#      或 CLI 抓一个 anomaly_id 后取根因分析：
AID=$(python - <<'PY'
import json, websocket
ws = websocket.create_connection("ws://localhost:8080/ws/anomalies")
msg = json.loads(ws.recv()); ws.close()
print(msg.get("anomaly_id") or msg.get("anomalyId") or "")
PY
)
[ -n "$AID" ] && curl -s "http://localhost:8000/anomalies/$AID/analysis" ; echo

# 5) 触发补偿（白名单动作 REFUND；非法动作返回 400）
curl -s -X POST http://localhost:8080/compensations -H "Content-Type: application/json" \
  -d "{\"actionType\":\"REFUND\",\"aggregateId\":\"$OID\"}" ; echo
```

对应前端操作：订单列表查看/筛选 → NL 查询框输入中文 → 异常看板查看告警并点开根因 → 补偿执行页选动作类型并确认。

## 已知限制 / Roadmap

**MVP 上限（当前）**

- AI 服务**无** `GET /anomalies` 列表接口：异常仅经 WebSocket 推送给前端，根因分析走 `GET /anomalies/{id}/analysis`（`ponytail:` 已知上限，暂无历史告警列表存储）。
- LLM 根因为可选增强：无 Ollama 时走关键词 / 数据摘要兜底，不调用大模型。
- 补偿为**人工触发**：`CompensationsController` 走白名单（REFUND / NOTIFY_DELAY / MARK_OUT_OF_STOCK / FREEZE_ORDER / BACKOFF_AND_STOP），动作 `execute` 仅产出人工可读描述，**不接真实支付 / 通知网关**，无 Saga 编排或审批流。
- 端到端测试默认跳过 Testcontainers 集成测试（需本地 Docker 资源），单元 / 组件测试覆盖命令、查询、AI、前端视图。
- 服务**端点无鉴权**，定位为本地演示 / 面试项目，未做认证授权。

**Roadmap（V2）**

- Saga 补偿编排：跨服务自动补偿与回滚，替代当前人工触发。
- 端点鉴权：引入认证 / 授权，收敛未受保护的 REST 与 WS。
- AI 异步化：检测与根因分析异步流水线，解耦 Kafka 消费与推理延迟。
- 真实支付网关：补偿动作对接真实支付 / 库存 / 通知外部系统。
