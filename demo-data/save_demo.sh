#!/usr/bin/env bash
# 保存当前演示快照：数据库 + AI 异常存储 + 校准后的 IF 模型
# 用法：bash demo-data/save_demo.sh
set -euo pipefail
cd "$(dirname "$0")/.."            # 仓库根目录
DEMO_DIR="demo-data"
mkdir -p "$DEMO_DIR/models"

echo "[*] 1/3 备份数据库 eventguard -> $DEMO_DIR/eventguard_dump.sql"
docker compose exec -T postgres \
  pg_dump -U eventguard -d eventguard --no-owner --no-privileges \
  > "$DEMO_DIR/eventguard_dump.sql"

echo "[*] 2/3 备份 AI 异常存储 -> $DEMO_DIR/anomalies.jsonl"
cp ai-data/anomalies.jsonl "$DEMO_DIR/anomalies.jsonl"

echo "[*] 3/3 备份校准后的 IF 模型 -> $DEMO_DIR/models/"
cp ai-data/models/isolation_forest.pkl "$DEMO_DIR/models/" 2>/dev/null || echo "  (警告) ai-data/models 下未找到 IF 模型，跳过"
cp ai-data/models/scaler.pkl "$DEMO_DIR/models/" 2>/dev/null || true

echo "[+] 快照保存完成："
ls -la "$DEMO_DIR"
ls -la "$DEMO_DIR/models"
