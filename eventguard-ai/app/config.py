from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    app_name: str = "EventGuard AI"
    kafka_bootstrap: str = "kafka:9092"
    kafka_group_id: str = "ai-event-detector"
    rule_engine_url: str = "http://eventguard-server:8080/anomaly/rules/evaluate"
    llm_base_url: str = "http://ollama:11434/v1"
    llm_api_key: str = "ollama"
    llm_model: str = "qwen2.5:7b"
    # LLM 提供商：留空则按 base_url 自动探测（含 "/anthropic" → anthropic，否则 openai）
    llm_provider: str = ""
    llm_max_tokens: int = 2048
    llm_temperature: float = 0.3
    llm_max_concurrency: int = 8
    llm_retry_attempts: int = 2
    llm_retry_backoff_seconds: float = 0.5
    # Item 8：根因分析是否注入相似案例 few-shot（默认关，开则每次分析前检索相似案例并入 prompt）
    ai_rag_fewshot: bool = False
    # 流程级检测阈值（与 Java 侧规则阈值可配的风格对齐，改阈值不必改代码）
    # P002 停滞：订单停在 PAID 超过该小时数视为停滞
    stagnation_timeout_hours: int = 24
    # P003 死循环：支付重试次数超过该值视为死循环
    dead_loop_threshold: int = 5
    # 规则引擎 HTTP 桥接超时（秒）：超时即降级为纯模型检测，不阻塞消费线程
    rule_bridge_timeout_seconds: float = 2.0
    # NL 查询 LLM 润色超时（秒）：超时降级为数据摘要
    nl_answer_timeout_seconds: float = 8.0
    model_dir: str = "models"
    server_base_url: str = "http://eventguard-server:8080"
    # 用户 JWT 校验密钥（与 Java 后端共用 EG_JWT_SECRET，HS256）；生产必须注入强随机值
    jwt_secret: str = "eventguard-dev-secret-change-me-0123456789abcdef"
    # 机器密钥：AI→后端内部调用（X-API-Key）用，与 Java 侧 EG_MACHINE_API_KEY 一致
    machine_api_key: str = "dev-machine-key"

    class Config:
        env_prefix = "EG_"
        env_file = ".env"


settings = Settings()

# 运行时配置只存在当前 AI 进程内：默认值仍来自启动时的 `.env`，避免把 API key
# 写入数据库或浏览器。重启服务即可恢复环境变量配置。
_DEFAULT_LLM_CONFIG = {
    "llm_base_url": settings.llm_base_url,
    "llm_api_key": settings.llm_api_key,
    "llm_model": settings.llm_model,
    "llm_provider": settings.llm_provider,
    "llm_max_tokens": settings.llm_max_tokens,
    "llm_temperature": settings.llm_temperature,
}


def llm_config() -> dict:
    """返回当前 LLM 配置；调用方负责在响应层掩码 api key。"""
    return {key: getattr(settings, key) for key in _DEFAULT_LLM_CONFIG}


def default_llm_config() -> dict:
    return dict(_DEFAULT_LLM_CONFIG)


def update_llm_config(values: dict) -> dict:
    for key in _DEFAULT_LLM_CONFIG:
        if key in values and values[key] is not None:
            setattr(settings, key, values[key])
    return llm_config()


def reset_llm_config() -> dict:
    return update_llm_config(_DEFAULT_LLM_CONFIG)
