from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="AGENT_", extra="ignore")

    app_name: str = "aiAgent-python"
    environment: str = "development"
    java_base_url: str = "http://127.0.0.1:9090"
    shared_secret: str = Field(default="dev-agent-secret", min_length=8)
    signature_max_clock_skew_seconds: int = Field(default=300, ge=1, le=3600)
    request_timeout_seconds: float = Field(default=120.0, gt=0)
    database_url: str | None = None
    tavily_api_key: str | None = None


@lru_cache
def get_settings() -> Settings:
    return Settings()

