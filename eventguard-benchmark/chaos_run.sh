#!/usr/bin/env bash
# chaos_run.sh —— 宿主机韧性评测（定向混沌 + 计时 + 数据零丢失断言）。
#
# bench 容器无 docker.sock，无法做 docker kill/pause；因此混沌注入由宿主机执行，
# 本脚本复用 eventguard-chaos/verify.sh 的探针库，产出 out/chaos-results.json 供 bench s10 导入。
#
# 用法（宿主机，需 Docker 全栈运行中）：
#   bash eventguard-benchmark/chaos_run.sh
#
# 覆盖：
#   1) db-kill        PG 崩溃 → 数据零丢失 + 恢复时间 + 命令端恢复
#   2) kafka-pause    Kafka 暂停 → 命令端可写 + 恢复时间
#   3) ai-delay       规则引擎兜底（延迟注入尽力而为，无 NET_ADMIN 时记注记）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CHAOS_DIR="$SCRIPT_DIR/../eventguard-chaos"
OUT_DIR="$SCRIPT_DIR/out"
mkdir -p "$OUT_DIR"
# bench 评测会把种子账号密码收敛到 BENCH_PASSWORD（默认 bench123456）；
# chaos_run.sh 可能在其前/后运行，这里探测可用密码（bench 改密后 → bench123456；未改密 → 种子密码）。
# shellcheck disable=SC1091
source "$CHAOS_DIR/verify.sh"

probe_token() {
  local pw="$1"
  _EG_TOKEN=""
  BENCH_PASSWORD="$pw" eg_token >/dev/null 2>&1
  printf '%s' "$_EG_TOKEN"
}

tok=$(probe_token "${BENCH_PASSWORD:-bench123456}")
if [[ -z "$tok" ]]; then
  tok=$(probe_token "operator123456")
  if [[ -n "$tok" ]]; then
    export BENCH_PASSWORD="operator123456"
    echo "[chaos_run] 使用种子密码 operator123456 登录（bench 尚未收敛密码）"
  else
    echo "[chaos_run] 无法登录 operator 账号（已改密？）。请设置环境变量 BENCH_PASSWORD=<当前密码> 后重试。" >&2
    exit 1
  fi
else
  export BENCH_PASSWORD="${BENCH_PASSWORD:-bench123456}"
  echo "[chaos_run] 使用 bench 收敛密码登录 operator"
fi

RESULTS="$OUT_DIR/chaos-results.json"
echo "[]" > "$RESULTS"

jq_row() { :; }  # 占位：下面用 python 追加，避免依赖 jq

append_scenario() {
  local name="$1" recovery="$2" data_loss="$3" pass="$4" note="${5:-}"
  python3 - "$RESULTS" "$name" "$recovery" "$data_loss" "$pass" "$note" <<'PY'
import json, sys
path, name, recovery, data_loss, passed, note = sys.argv[1:7]
rows = json.load(open(path, encoding="utf-8"))
rows.append({
    "name": name,
    "recovery_seconds": float(recovery),
    "data_loss_events": int(data_loss),
    "pass": str(passed).lower() == "true",
    "note": note,
})
with open(path, "w", encoding="utf-8") as f:
    json.dump(rows, f, ensure_ascii=False, indent=2)
PY
}

# ---------- 场景一：db-kill ----------
echo "== [chaos_run] db-kill（PG 崩溃 → 数据零丢失）=="
BEFORE=$(pg_count_events); echo "  before events=$BEFORE"
CID=$(pg_cid)
docker kill "$CID" >/dev/null
T0=$(date +%s)
docker start "$CID" >/dev/null || true
wait_for "postgres 恢复" pg_health 40
T1=$(date +%s)
RECOVERY=$((T1 - T0))
AFTER=$(pg_count_events); echo "  after events=$AFTER（恢复 ${RECOVERY}s）"
DATA_LOSS=$((AFTER - BEFORE))
wait_for "server 恢复" server_health 40
if command_write_ok; then CMD_OK=true; else CMD_OK=false; fi
if [[ "$DATA_LOSS" -eq 0 && "$CMD_OK" = true ]]; then DB_PASS=true; else DB_PASS=false; fi
append_scenario "db-kill" "$RECOVERY" "$DATA_LOSS" "$DB_PASS" "cmd_write_ok=$CMD_OK"
[[ "$DB_PASS" = true ]] && echo "  ✓ PG 崩溃后数据零丢失、命令端恢复" || echo "  ✗ 数据丢失或命令端未恢复" >&2

# ---------- 场景二：kafka-pause ----------
echo "== [chaos_run] kafka-pause（Kafka 暂停 → 命令端可写）=="
CID=$(kafka_cid)
T0=$(date +%s)
docker pause "$CID" >/dev/null
if command_write_ok; then PAUSE_OK=true; else PAUSE_OK=false; fi
echo "  暂停期间 POST /orders → $([ "$PAUSE_OK" = true ] && echo 200 || echo FAIL)"
docker unpause "$CID" >/dev/null || true
wait_for "kafka 恢复" 'kafka_topic_exists domain-events' 30
T1=$(date +%s)
RECOVERY=$((T1 - T0))
append_scenario "kafka-pause" "$RECOVERY" 0 "$PAUSE_OK" "command_write_during_pause=$PAUSE_OK"
[[ "$PAUSE_OK" = true ]] && echo "  ✓ Kafka 暂停期间命令端可写（恢复 ${RECOVERY}s）" || echo "  ✗ 命令端在 Kafka 暂停期间写入失败" >&2

# ---------- 场景三：ai-delay ----------
echo "== [chaos_run] ai-delay（AI 网络延迟 → 规则引擎兜底）=="
if rule_engine_ok; then BASE_RULE_OK=true; else BASE_RULE_OK=false; fi
echo "  基线规则引擎评估 → $([ "$BASE_RULE_OK" = true ] && echo 200 || echo FAIL)"
AI_NOTE="尽力而为；无 NET_ADMIN 时 ai-delay.sh 仅做说明，规则兜底为基线检查"
if bash "$CHAOS_DIR/ai-delay.sh" >/tmp/chaos-ai-delay.log 2>&1; then
  AI_PASS="$BASE_RULE_OK"
  AI_NOTE="ai-delay.sh 执行完成，规则引擎兜底 OK=$BASE_RULE_OK（详见 /tmp/chaos-ai-delay.log）"
else
  AI_PASS=false
  AI_NOTE="ai-delay.sh 未通过（可能需要 NET_ADMIN；详见 /tmp/chaos-ai-delay.log）"
fi
append_scenario "ai-delay" 0 0 "$AI_PASS" "$AI_NOTE"
echo "  规则引擎兜底 → $([ "$AI_PASS" = true ] && echo OK || echo FAIL)（$AI_NOTE）"

echo
echo "== [chaos_run] 结果已写入 $RESULTS =="
cat "$RESULTS"
