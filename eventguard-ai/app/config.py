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

    class Config:
        env_prefix = "EG_"
        env_file = ".env"


settings = Settings()
