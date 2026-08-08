"""Prometheus 业务指标（评测模块可观测数据的基础）。

- 使用 prometheus_client 默认 registry，FastAPI 侧经 GET /metrics 暴露（prometheus.yml 抓取）。
- 指标前缀统一 `eventguard_ai_*`，与 server 侧 `eventguard_*` 呼应。
- 纯埋点，不改业务行为；任何埋点异常都不应阻断检测/查询（调用方以不抛出为原则）。
"""
import time  # noqa: F401  （保留，便于后续取 wall-clock）

from prometheus_client import Counter, Gauge, Histogram

# ===== 检测管道（app/kafka_consumer.py DetectionHandler）=====
events_consumed = Counter(
    "eventguard_ai_events_consumed_total",
    "检测管道消费的事件总数",
)
detection_latency = Histogram(
    "eventguard_ai_detection_latency_seconds",
    "单条事件检测处理耗时（收到事件 → 检测/发布完成）",
    buckets=(0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0),
)
anomalies_published = Counter(
    "eventguard_ai_anomalies_published_total",
    "发布的异常数（按规则/来源/级别）",
    ["rule_id", "source", "level"],
)
publish_errors = Counter(
    "eventguard_ai_publish_errors_total",
    "发布异常到 Kafka 失败次数",
)
alert_dedup_total = Counter(
    "eventguard_ai_alert_dedup_total",
    "告警去重/风暴抑制跳过的发布数",
    ["reason"],  # dup / suppressed
)
rule_bridge_errors = Counter(
    "eventguard_ai_rule_bridge_errors_total",
    "Java 规则引擎桥接调用失败次数",
)
detector_running = Gauge(
    "eventguard_ai_detector_running",
    "检测管道运行状态（1=运行 0=未运行/降级）",
)

# ===== NL 查询（app/query/nl_query_engine.py）=====
nl_query_duration = Histogram(
    "eventguard_ai_nl_query_duration_seconds",
    "NL 查询处理耗时",
    ["intent"],
    buckets=(0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0),
)
nl_query_total = Counter(
    "eventguard_ai_nl_query_total",
    "NL 查询次数（fallback=true 表示 LLM 润色失败走数据摘要兜底）",
    ["intent", "fallback"],
)

# ===== LLM 层（Item 4：app/analyzer/llm_client.py + app/cache/llm_cache.py）=====
llm_cache_hits = Counter(
    "eventguard_ai_llm_cache_hits_total",
    "LLM 响应缓存命中次数",
)
llm_cache_misses = Counter(
    "eventguard_ai_llm_cache_misses_total",
    "LLM 响应缓存未命中次数",
)
llm_tokens = Counter(
    "eventguard_ai_llm_tokens_total",
    "LLM 消耗 token 数（含输入+输出）",
    ["model", "operation"],
)
llm_calls = Counter(
    "eventguard_ai_llm_calls_total",
    "LLM 调用次数（ok=true 成功 / false 失败）",
    ["provider", "operation", "ok"],
)
