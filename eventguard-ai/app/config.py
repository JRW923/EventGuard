from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    app_name: str = "EventGuard AI"
    kafka_bootstrap: str = "kafka:9092"

    class Config:
        env_prefix = "EG_"
        env_file = ".env"


settings = Settings()
