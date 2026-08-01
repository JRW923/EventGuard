from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    app_name: str = "EventGuard AI"
    kafka_bootstrap: str = "kafka:9092"
    kafka_group_id: str = "ai-event-detector"
    rule_engine_url: str = "http://eventguard-server:8080/anomaly/rules/evaluate"
    llm_base_url: str = "http://ollama:11434/v1"
    llm_api_key: str = "ollama"
    llm_model: str = "qwen2.5:7b"
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
