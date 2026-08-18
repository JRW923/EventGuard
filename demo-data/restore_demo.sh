#!/usr/bin/env bash
# 一键恢复演示快照：数据库 + AI 异常存储 + IF 模型，并重置 Kafka 消费组 offset
# 用法：bash demo-data/restore_demo.sh
set -euo pipefail
cd "$(dirname "$0")/.."            # 仓库根目录
DEMO_DIR="demo-data"
DUMP="$DEMO_DIR/eventguard_dump.sql"
ANOM="$DEMO_DIR/anomalies.jsonl"

[ -f "$DUMP" ] || { echo "[!] 找不到快照 $DUMP，请先运行 save_demo.sh"; exit 1; }

echo "[*] 1/5 停止消费者 eventguard-ai / eventguard-server ..."
docker compose stop eventguard-ai eventguard-server

echo "[*] 2/5 恢复数据库 eventguard ..."
# DROP SCHEMA 清空所有表（保留 DB / 发布 / 复制槽），再灌入快照
docker compose exec -T postgres psql -U eventguard -d eventguard \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
docker compose exec -T postgres psql -U eventguard -d eventguard \
  < "$DUMP" >/dev/null

echo "[*] 3/5 恢复 AI 异常存储 anomalies.jsonl ..."
cp "$ANOM" ai-data/anomalies.jsonl

echo "[*] 4/5 恢复 IF 模型（若存在）..."
mkdir -p ai-data/models
[ -f "$DEMO_DIR/models/isolation_forest.pkl" ] && cp "$DEMO_DIR/models/isolation_forest.pkl" ai-data/models/
[ -f "$DEMO_DIR/models/scaler.pkl" ] && cp "$DEMO_DIR/models/scaler.pkl" ai-data/models/

echo "[*] 5/5 重置 Kafka 消费组 offset 到 latest（跳过恢复产生的 CDC）..."
docker compose run --rm -v "$PWD/$DEMO_DIR:/mnt" eventguard-ai python /mnt/reset_offsets.py

echo "[*] 重启 eventguard-ai / eventguard-server ..."
docker compose start eventguard-ai eventguard-server

echo "[+] 恢复完成。等待服务就绪后看板应显示 R001/R004/R005/P001 共 6 条告警。"
