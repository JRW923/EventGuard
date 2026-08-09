# EventGuard 混沌实验（M5.2）

用 Docker 故障注入验证 EventGuard 的韧性：数据库崩溃不丢数据、消息总线暂停命令端仍可写、
AI 链路延迟时规则引擎兜底。三种故障均对应计划 `docs/架构设计/eventguard-plan.md` 的 M5.2 验收点。

## 前置条件

- 已安装 Docker + 本仓库全栈起在运行中：

  ```bash
  cp .env.example .env
  docker compose up -d --build
  # 等待所有服务 healthy
  ```

- `docker compose ps` 能看到 `postgres` / `kafka` / `eventguard-ai` / `eventguard-server` 等。
- 可选：拉取 pumba 镜像用于 AI 延迟注入（`docker pull gaiaadm/pumba`），
  `ai-delay.sh` 会优先用 pumba，缺失时回退到容器内 `tc netem`。

> ponytail: 本目录所有脚本都依赖「运行中的 Docker 全栈」。本机无 Docker / 未起全栈时
> 只能做 `bash -n` 语法检查，无法实跑——实跑需在具备 Docker 的环境执行。

## 运行方式

各脚本 `source` 同目录的 `verify.sh`（通用校验函数库），也可单独跑 `verify.sh` 看基线健康：

```bash
bash verify.sh                 # 打印全栈基线状态
bash db-kill.sh                # 场景一：PG 崩溃 → 数据不丢
bash kafka-pause.sh            # 场景二：Kafka 暂停 → 命令端可写
bash ai-delay.sh               # 场景三：AI 延迟 → 规则引擎兜底
```

### 与 Pumba 的关系

`docker-compose.yml` 已内置 `pumba` 服务（`profiles: ["chaos"]`），`--random --interval 60s kill`
会每 60s 随机杀容器做无差别韧性测试。本目录脚本是**定向、可断言**的版本：

- `db-kill.sh` 的 `docker kill postgres` 等效于 pumba 对 postgres 的 kill 故障；
- `kafka-pause.sh` 的 `docker pause kafka` 等效于 pumba 的 pause 故障；
- `ai-delay.sh` 直接用 pumba 的 `delay` 子命令注入网络延迟（或容器内 `tc netem`）。

如需直接起无差别 pumba：

```bash
docker compose --profile chaos up -d pumba
```

## 三种故障的预期降级 / 恢复行为

| 故障 | 注入 | 预期降级 | 预期恢复 |
|------|------|----------|----------|
| PG 崩溃 | `docker kill postgres` | 命令端短暂不可用，事件写入中断 | `docker start` 后 PG 从命名卷 `pgdata` 恢复，`domain_events` 行数不变；命令端继续写入 |
| Kafka 暂停 | `docker pause kafka` | 下单 `POST /orders` 仍 200（写 PG，不依赖 Kafka）；查询投影 / AI 检测暂时滞后 | `docker unpause` 后 Debezium CDC 从最新位点补发，投影与检测最终一致 |
| AI 网络延迟 | pumba `delay 5000ms` | 高优先级规则引擎（`POST /anomaly/rules/evaluate`，命令端本地）仍可独立判定；AI 的 IsolationForest 检测变慢 | 延迟窗口结束后 AI 链路恢复 |

## 已知上限（ponytail）

- `db-kill.sh` 仅覆盖「容器崩溃 + 卷持久化」，**不**覆盖「卷本身损坏 / 丢失」的极端数据丢失。
- `kafka-pause.sh` 仅验证暂停期间的命令可用性，未压测长暂停后的 CDC 积压重放压力。
- `ai-delay.sh` 的网络延迟注入需要特权能力；无 NET_ADMIN 时仅打印提示、做规则引擎兜底说明，
  不会真正注入延迟。
- 校验断言依赖真实端点路径（见 `verify.sh` 中 `command_write_ok` / `rule_engine_ok`），
  若控制器路径变更需同步更新。
