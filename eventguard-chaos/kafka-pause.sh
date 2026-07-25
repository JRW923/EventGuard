#!/usr/bin/env bash
# kafka-pause.sh —— 模拟 Kafka 被暂停（docker pause），验证命令端仍可写入。
#
# 对齐计划 M5.2：pumba pause kafka，验证命令端仍可写。
# 命令端写入走 PostgreSQL 事件库（EventStore.append），不依赖 Kafka；
# Kafka 仅承担 CDC 流出（Debezium）与 AI 实时检测。因此暂停 Kafka 时：
#   - 下单（POST /orders）仍返回 200，事件已持久化到 PG；
#   - 查询投影 / AI 检测会暂时滞后，恢复后由 CDC 补发追平（最终一致）。
#
# 前置：docker compose up -d --build 且 kafka 健康。
# ponytail: 仅验证「Kafka 暂停期间命令可用性」，不验证长暂停后的 CDC 积压重放压力。

set -euo pipefail
source "$(dirname "$0")/verify.sh"

CID=$(kafka_cid)
echo "== [kafka-pause] 暂停 kafka 容器 ($CID) =="
docker pause "$CID"

echo "== [kafka-pause] 验证命令端仍可写（POST /orders 绕开 Kafka）=="
if command_write_ok; then
  echo "✓ 命令端在 Kafka 暂停期间仍可写入（POST /orders → 200）；事件已落入 PG 事件库"
else
  echo "✗ 命令端写入失败（不应发生：写路径不依赖 Kafka）" >&2
  docker unpause "$CID" || true
  exit 1
fi

echo "== [kafka-pause] 恢复 kafka 容器 =="
docker unpause "$CID"
wait_for "kafka" 'kafka_topic_exists domain-events' 30

echo "✓ Kafka 恢复；CDC 与 AI 检测将自最新位点继续（最终一致）"
