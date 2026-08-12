"""评测编排器：preflight → 功能套件 → 负载套件 → 韧性导入 → 汇总渲染。"""
from __future__ import annotations

import json
import time
import uuid
from pathlib import Path

from .auth import Auth
from .client import ApiClient
from .config import Config
from .db import Db
from .prometheus import Prometheus


class Context:
    """套件共享上下文：端点客户端 / 令牌 / DB / 运行元信息。"""

    def __init__(self, cfg: Config) -> None:
        self.cfg = cfg
        self.client = ApiClient(cfg.server_base)
        # AI 服务独立端点（/ai/* 路由在 eventguard-ai:8000，不走 server 反向代理）
        self.ai_client = ApiClient(cfg.ai_base)
        self.auth = Auth(self.client, cfg)
        self.db = Db(cfg)
        self.prom = Prometheus(cfg.prometheus_url)
        self.run_id = cfg.run_id
        self.mode: dict = {}

    def close(self) -> None:
        self.client.close()
        self.db.close()


def _is_up(url: str, path: str = "/", status_ok=(200, 404)) -> bool:
    import requests

    try:
        r = requests.get(url + path, timeout=5)
        return r.status_code in status_ok
    except requests.RequestException:
        return False


def preflight(ctx: Context) -> None:
    """健康检查 + 登录 + mode 探测 + CDC 预热。失败抛异常中止评测。"""
    print("[preflight] 健康检查…")
    if not _is_up(ctx.cfg.server_base, "/actuator/health", status_ok=(200,)):
        raise RuntimeError(f"server 不可达：{ctx.cfg.server_base}/actuator/health")
    if not _is_up(ctx.cfg.ai_base, "/health", status_ok=(200,)):
        raise RuntimeError(f"AI 不可达：{ctx.cfg.ai_base}/health")
    ctx.db.connect()
    if ctx.db.scalar("SELECT 1") is None:
        raise RuntimeError("Postgres 不可达（SELECT 1 失败）")
    if not ctx.prom.healthy():
        print("  [warn] Prometheus 不可达（看板/交叉验证降级，延迟数据以 bench 自身为准）")

    print("[preflight] 登录 + 种子账号密码收敛…")
    ctx.auth.ensure_roles()

    # —— mode 探测 ——
    mode = {
        "llm": "configured" if _env("EG_LLM_BASE_URL") and _env("EG_LLM_API_KEY") else "absent",
        "rate_limit": _env("EG_RATE_LIMIT_ENABLED", "true").lower() != "false",
        "payment_delay_ms": int(_env("EG_GATEWAY_MOCK_PAYMENT_DELAY_MS", "0")),
        "saga": _env("EG_SAGA_ENABLED", "true").lower() != "false",
    }
    if mode["rate_limit"]:
        threshold = _probe_rate_limit(ctx)
        mode["measured_threshold"] = threshold
    else:
        print("  [preflight] 限流关闭（EG_RATE_LIMIT_ENABLED=false），负载套件可运行")
    ctx.mode = mode

    # —— CDC 预热：probe 下单 → 投影追上 ——
    print("[preflight] CDC/投影预热…")
    _warm_cdc(ctx)


def _env(name: str, default: str | None = None) -> str | None:
    import os

    v = os.environ.get(name)
    return v if v not in (None, "") else default


def _probe_rate_limit(ctx: Context) -> int:
    """突发 max+5 次只读请求，实测限流阈值（429 起始位）。随后睡 10s 让窗口复位。"""
    import requests

    token = ctx.auth.token("operator")
    n = ctx.cfg.rate_limit_max + 5
    first_429: int | None = None
    for i in range(1, n + 1):
        r = requests.get(ctx.cfg.server_base + "/orders", headers={"Authorization": f"Bearer {token}"},
                         timeout=10)
        if r.status_code == 429 and first_429 is None:
            first_429 = i
    print(f"  [preflight] 限流实测：第 {first_429 if first_429 else '—（未触发）'} 次请求起 429（max={ctx.cfg.rate_limit_max}）")
    time.sleep(10)  # 复位滑动窗口，避免影响功能套件
    return first_429 if first_429 is not None else n


def _warm_cdc(ctx: Context) -> None:
    oid = str(uuid.uuid4())
    ctx.client.post("/orders", token=ctx.auth.token("operator"),
                    json={"orderId": oid, "userId": f"bench-warm-{ctx.run_id}", "totalAmount": 1.0})
    deadline = time.time() + ctx.cfg.cdc_warmup_seconds
    ok = False
    while time.time() < deadline:
        r, _ = ctx.client.get(f"/orders/{oid}?expectedVersion=1", token=ctx.auth.token("operator"))
        if r.status_code == 200:
            ok = True
            break
        time.sleep(0.5)
    if not ok:
        raise RuntimeError(f"CDC/投影预热超时（{ctx.cfg.cdc_warmup_seconds}s 内 probe 订单未投影）")
    print("  [preflight] CDC/投影就绪")


def load_chaos_results(out_dir: str) -> dict:
    """导入宿主机 chaos_run.sh 产出的韧性结果（缺失则空 dict）。

    chaos_run.sh 产出的是 JSON 数组；统一包成 {"scenarios": [...]}，
    供 run.py / report 各处按 dict 消费（此前数组直返导致 KPI 汇总崩）。
    """
    p = Path(out_dir) / "chaos-results.json"
    if not p.exists():
        return {}
    try:
        raw = json.loads(p.read_text(encoding="utf-8"))
        if isinstance(raw, list):
            return {"scenarios": raw}
        return raw
    except (json.JSONDecodeError, OSError):
        return {}
