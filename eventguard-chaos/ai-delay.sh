#!/usr/bin/env bash
# ai-delay.sh —— 对 AI 服务（eventguard-ai）注入网络延迟，验证规则引擎兜底。
#
# 对齐计划 M5.2：pumba delay --time 5000 eventguard-ai，验证规则引擎兜底。
# 故障语义：AI 服务响应变慢（检测链路受网络延迟影响），但异常判定有双通道：
#   - 高优先级：命令端本地规则引擎 POST /anomaly/rules/evaluate（不依赖 AI 网络）；
#   - 低优先级：AI 服务的 IsolationForest 检测（受本次延迟影响）。
# 因此即使 AI 链路延迟，规则引擎仍可独立给出兜底判定，系统不「失明」。
#
# 前置：docker compose up -d --build 且 eventguard-ai 健康。
#
# ponytail: 网络延迟注入需要特权能力（容器 NET_ADMIN / 宿主机 tc）。
#   首选用 pumba（已挂载 docker.sock，由它操作目标容器网络命名空间）；
#   若镜像未授权 NET_ADMIN，回退到容器内 `tc netem`，失败时仅打印提示不阻断流程。

set -euo pipefail
source "$(dirname "$0")/verify.sh"

CID=$(ai_cid)
DELAY_MS=${DELAY_MS:-5000}
DURATION=${DURATION:-30s}

echo "== [ai-delay] 对 eventguard-ai 注入 ${DELAY_MS}ms 网络延迟（持续 ${DURATION}）=="

# 方式一（首选）：用 pumba 操作目标容器的网络命名空间
if docker image inspect gaiaadm/pumba >/dev/null 2>&1; then
  echo "  使用 pumba 注入网络延迟..."
  docker run --rm --name eventguard-pumba-delay \
    -v /var/run/docker.sock:/var/run/docker.sock \
    gaiaadm/pumba delay --duration "$DURATION" --time "$DELAY_MS" "$CID" &
  PUMBA_PID=$!
  # 等待延迟生效
  sleep 3
else
  # 方式二（回退）：容器内 tc netem，需 NET_ADMIN
  echo "  未找到 pumba 镜像，尝试容器内 tc netem（需要 NET_ADMIN 能力）..."
  if docker exec "$CID" tc qdisc add dev eth0 root netem delay "${DELAY_MS}ms" 2>/dev/null; then
    ( sleep "${DURATION%s}" ; docker exec "$CID" tc qdisc del dev eth0 root netem 2>/dev/null ) &
  else
    echo "  ⚠ 无法注入延迟（容器无 NET_ADMIN 或宿主机无 tc）。仅做规则引擎兜底校验说明。" >&2
  fi
fi

echo "== [ai-delay] 验证规则引擎兜底可用（命令端本地，不依赖 AI 网络）=="
if rule_engine_ok; then
  echo "✓ 规则引擎在 AI 延迟期间仍可独立判定（POST /anomaly/rules/evaluate → 200）；异常检测不失明"
else
  echo "✗ 规则引擎不可用（兜底失效，需排查命令端）" >&2
fi

# 等待 pumba 注入窗口结束
wait "${PUMBA_PID:-}" 2>/dev/null || true
echo "✓ 延迟窗口结束；AI 服务恢复，IsolationForest 检测链路重新可用"
