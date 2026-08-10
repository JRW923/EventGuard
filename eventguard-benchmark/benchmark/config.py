"""bench 评测器共享配置：全部从环境变量读取，默认对齐 compose 网络内的服务名。

在 bench 容器内运行时，compose 注入 SERVER_BASE_URL/AI_BASE_URL/KAFKA_BOOTSTRAP/... 等；
本地（无容器）调试时默认 localhost。
"""
from __future__ import annotations

import os


class Config:
    def __init__(self) -> None:
        # —— 服务端点 ——
        self.server_base = os.environ.get("SERVER_BASE_URL", "http://localhost:8080")
        self.ai_base = os.environ.get("AI_BASE_URL", "http://localhost:8000")
        self.kafka_bootstrap = os.environ.get("KAFKA_BOOTSTRAP", "localhost:9092")
        self.prometheus_url = os.environ.get("PROMETHEUS_URL", "http://localhost:9090")

        # —— Postgres（直读 domain_events / order_view / notification_log 等做断言）——
        self.pg_host = os.environ.get("POSTGRES_HOST", "localhost")
        self.pg_port = os.environ.get("POSTGRES_PORT", "5432")
        self.pg_user = os.environ.get("POSTGRES_USER", "eventguard")
        self.pg_password = os.environ.get("POSTGRES_PASSWORD", "eventguard")
        self.pg_db = os.environ.get("POSTGRES_DB", "eventguard")

        # —— 身份 ——
        self.machine_key = os.environ.get("EG_MACHINE_API_KEY", "dev-machine-key")
        self.jwt_secret = os.environ.get("EG_JWT_SECRET", "dev-jwt-secret")
        self.admin_user = os.environ.get("BENCH_ADMIN_USER", "admin")
        self.admin_password = os.environ.get("BENCH_ADMIN_PASSWORD", "admin123456")
        self.operator_user = os.environ.get("BENCH_OPERATOR_USER", "operator")
        self.operator_password = os.environ.get("BENCH_OPERATOR_PASSWORD", "operator123456")
        self.viewer_user = os.environ.get("BENCH_VIEWER_USER", "viewer")
        self.viewer_password = os.environ.get("BENCH_VIEWER_PASSWORD", "viewer123456")
        # 首次运行把种子密码改为该稳定值（幂等：已改过则直接用）
        self.bench_password = os.environ.get("BENCH_PASSWORD", "bench123456")

        # —— 运行参数 ——
        self.suites = os.environ.get("BENCH_SUITES", "functional")  # functional | load | all
        self.out_dir = os.environ.get("BENCH_OUT", "/out")
        self.rate_limit_max = int(os.environ.get("EG_RATE_LIMIT_MAX", "60"))
        # 功能套件自节奏：默认 250ms/请求（≤4 req/s），配合有界轮询把单 10s 窗口压在限流阈值内
        self.pacing_ms = float(os.environ.get("BENCH_PACING_MS", "250"))
        # 套件间 settle：跨过 per-IP 10s 固定窗口边界，避免前一套件请求堆满窗口导致下一套件 429
        # （s05 提速后 s04+s05+s06 曾同窗堆积、s07 启动即 429）
        self.inter_suite_settle_seconds = float(os.environ.get("BENCH_INTER_SUITE_SETTLE_SECONDS", "11"))
        # 预检等待 CDC 投影追上的最长时间
        self.cdc_warmup_seconds = float(os.environ.get("BENCH_CDC_WARMUP_SECONDS", "60"))
        # 断言轮询超时（异步事件/回调）
        self.assert_timeout_seconds = float(os.environ.get("BENCH_ASSERT_TIMEOUT_SECONDS", "30"))
        self.run_id = os.environ.get("BENCH_RUN_ID", _default_run_id())

    @property
    def pg_dsn(self) -> str:
        return (
            f"host={self.pg_host} port={self.pg_port} dbname={self.pg_db} "
            f"user={self.pg_user} password={self.pg_password}"
        )


def _default_run_id() -> str:
    # 允许跨模块 import 时无时间源依赖；run_id 主要由 orchestrator 注入。
    return "bench-run"
