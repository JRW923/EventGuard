from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="EG_", env_file=".env", protected_namespaces=("settings_",), extra="ignore"
    )
    app_name: str = "EventGuard AI"
    kafka_bootstrap: str = "kafka:9092"
    kafka_group_id: str = "ai-event-detector"
    rule_engine_url: str = "http://eventguard-server:8080/anomaly/rules/evaluate"
    # LLM 配置已改为「按用户」：每个用户在自己的个人中心配置 base_url/api_key/model，
    # 存 Java 侧 PostgreSQL（AES 加密）。AI 服务不再从环境读取任何默认 LLM 配置。
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
    # 分析类端点端到端超时（秒）：/ai/heal 最多 5 步工具调用、/anomalies/{id}/analysis 单轮 LLM，
    # 不设上界时最坏可达数分钟，前端早已超时。超时返回 504。
    heal_timeout_seconds: float = 120.0
    analysis_timeout_seconds: float = 45.0
    # NL 查询 LLM 润色超时（秒）：超时降级为数据摘要
    nl_answer_timeout_seconds: float = 8.0
    nl_query_timeout_seconds: float = 8.0
    nl_intent_timeout_seconds: float = 2.0
    model_dir: str = "models"
    server_base_url: str = "http://eventguard-server:8080"
    # 用户 JWT 校验密钥（与 Java 后端共用 EG_JWT_SECRET，HS256）；生产必须注入强随机值
    jwt_secret: str = "eventguard-dev-secret-change-me-0123456789abcdef"
    # 机器密钥：AI→后端内部调用（X-API-Key）用，与 Java 侧 EG_MACHINE_API_KEY 一致
    machine_api_key: str = "dev-machine-key"
    # ponytail: Isolation Forest 模型/标准化器路径可配；默认读镜像内置 /app/models，
    # 演示环境指向挂载目录 /data/models（随 ai-data 卷持久化，重训后无需重建镜像即可生效）。
    if_model_path: str = ""
    if_scaler_path: str = ""

settings = Settings()
