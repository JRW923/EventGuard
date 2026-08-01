#!/usr/bin/env bash
# verify.sh —— 混沌实验通用校验函数库
#
# 被 db-kill.sh / kafka-pause.sh / ai-delay.sh 通过 `source` 复用；
# 也可单独运行 `bash verify.sh` 打印全栈基线健康状态。
#
# 设计约定：
# - 与 docker-compose.yml 的服务名对齐（postgres / kafka / eventguard-ai / eventguard-server）。
# - 所有可调项通过环境变量覆盖，默认对接本机已 `docker compose up -d --build` 的全栈。
#
# ponytail: 校验依赖「已在运行的 Docker 全栈 + .env 中导出的数据库凭据」。
#   本机无 Docker / 未起全栈时，这些函数会返回非零，脚本应在开头自检环境。

set -euo pipefail

# —— 可覆盖的环境变量 ——
COMPOSE_PROJECT=${COMPOSE_PROJECT:-eventguard}
PG_SERVICE=${PG_SERVICE:-postgres}
KAFKA_SERVICE=${KAFKA_SERVICE:-kafka}
AI_SERVICE=${AI_SERVICE:-eventguard-ai}
SERVER_SERVICE=${SERVER_SERVICE:-eventguard-server}
SERVER_PORT=${SERVER_PORT:-8080}
AI_PORT=${AI_PORT:-8000}
# 压测/探针账号：默认种子 OPERATOR（具备 order:create/order:write 等权限）
BENCH_USER=${BENCH_USER:-operator}
BENCH_PASSWORD=${BENCH_PASSWORD:-operator123456}

# 若仓库根目录存在 .env，则加载数据库凭据（POSTGRES_USER / POSTGRES_DB / POSTGRES_PASSWORD）。
# 这样 psql 才能无交互连上容器内数据库。
if [[ -f "$(dirname "$0")/../.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$(dirname "$0")/../.env"
  set +a
fi

DC="docker compose"

# —— 容器定位 ——
# docker compose ps -q <service> 返回容器 id；失败则回退到 目录名_服务名_1 命名约定。
pg_cid()      { local c; c=$($DC ps -q "$PG_SERVICE" 2>/dev/null); echo "${c:-${COMPOSE_PROJECT}-${PG_SERVICE}-1}"; }
kafka_cid()   { local c; c=$($DC ps -q "$KAFKA_SERVICE" 2>/dev/null); echo "${c:-${COMPOSE_PROJECT}-${KAFKA_SERVICE}-1}"; }
ai_cid()      { local c; c=$($DC ps -q "$AI_SERVICE" 2>/dev/null); echo "${c:-${COMPOSE_PROJECT}-${AI_SERVICE}-1}"; }

# —— 健康检查 ——
pg_health() {
  $DC exec -T "$PG_SERVICE" pg_isready -U "${POSTGRES_USER:-eventguard}" -d "${POSTGRES_DB:-eventguard}" >/dev/null 2>&1
}

server_health() {
  local code
  code=$(curl -fsS -o /dev/null -w "%{http_code}" "http://localhost:${SERVER_PORT}/actuator/health" 2>/dev/null || echo "000")
  [[ "$code" = "200" ]]
}

ai_health() {
  local code
  code=$(curl -fsS -o /dev/null -w "%{http_code}" "http://localhost:${AI_PORT}/health" 2>/dev/null || echo "000")
  [[ "$code" = "200" ]]
}

# —— 认证辅助 ——
# 登录换取用户 JWT（缓存到进程级变量，避免每个探针都重复登录）
_EG_TOKEN=""
eg_token() {
  if [[ -z "$_EG_TOKEN" ]]; then
    _EG_TOKEN=$(curl -fsS -X POST "http://localhost:${SERVER_PORT}/auth/login" \
      -H "Content-Type: application/json" \
      -d "{\"username\":\"${BENCH_USER}\",\"password\":\"${BENCH_PASSWORD}\"}" 2>/dev/null \
      | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  fi
  printf '%s' "$_EG_TOKEN"
}

# 机器密钥（来自 .env EG_MACHINE_API_KEY）：仅授 order:read / anomaly:evaluate 等受限权限
machine_key() {
  printf '%s' "${EG_MACHINE_API_KEY:-dev-machine-key}"
}

# —— 业务校验 ——
# domain_events 行数（验证事件溯源数据在故障前后不丢）
pg_count_events() {
  PGPASSWORD="${POSTGRES_PASSWORD:-eventguard}" $DC exec -T "$PG_SERVICE" \
    psql -U "${POSTGRES_USER:-eventguard}" -d "${POSTGRES_DB:-eventguard}" -t -A \
    -c "SELECT count(*) FROM domain_events;" 2>/dev/null || echo "0"
}

# Kafka topic 是否存在
kafka_topic_exists() {
  local topic="$1"
  $DC exec -T "$KAFKA_SERVICE" kafka-topics --bootstrap-server localhost:9092 --list 2>/dev/null \
    | grep -qx "$topic"
}

# 命令端可写：POST /orders 应返回 200（不依赖 Kafka，直接写 PG 事件库）；需 order:create 权限 → 用户 JWT
command_write_ok() {
  local token; token=$(eg_token)
  local code
  code=$(curl -fsS -o /dev/null -w "%{http_code}" -X POST "http://localhost:${SERVER_PORT}/orders" \
    -H "Content-Type: application/json" -H "Authorization: Bearer ${token}" \
    -d '{"userId":"chaos-probe","totalAmount":1.0}' 2>/dev/null || echo "000")
  [[ "$code" = "200" ]]
}

# 规则引擎兜底可用：POST /anomaly/rules/evaluate 由命令端本地处理，不依赖 AI 网络；机器密钥持有 anomaly:evaluate
rule_engine_ok() {
  local code
  code=$(curl -fsS -o /dev/null -w "%{http_code}" -X POST "http://localhost:${SERVER_PORT}/anomaly/rules/evaluate" \
    -H "Content-Type: application/json" -H "X-API-Key: $(machine_key)" \
    -d '{"eventId":"11111111-2222-3333-4444-555555555555","aggregateId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","eventType":"OrderCreatedEvent","version":1,"occurredAt":"2026-01-01T00:00:00Z","payload":{"userId":"chaos-probe","totalAmount":1.0}}' 2>/dev/null || echo "000")
  [[ "$code" = "200" ]]
}

# —— 等待辅助 ——
wait_for() {
  local desc="$1" check="$2" max="${3:-30}"
  for i in $(seq 1 "$max"); do
    if eval "$check"; then echo "  ✓ $desc 就绪 (${i}s)"; return 0; fi
    sleep 1
  done
  echo "  ✗ $desc 在 ${max}s 内未就绪" >&2
  return 1
}

# 单独运行时打印基线状态
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "== EventGuard 基线健康检查 =="
  echo -n "postgres      : "; pg_health && echo "ok ($(pg_count_events) events)" || echo "DOWN"
  echo -n "kafka         : "; kafka_topic_exists domain-events && echo "domain-events 存在" || echo "topic 缺失/DOWN"
  kafka_topic_exists anomaly-alerts && echo "                anomaly-alerts 存在" || echo "                anomaly-alerts 缺失"
  echo -n "eventguard-server: "; server_health && echo "ok" || echo "DOWN"
  echo -n "eventguard-ai : "; ai_health && echo "ok" || echo "DOWN"
fi
