from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str = "postgresql://calendar_app:calendar_app_password@localhost:5432/family_calendar"
    embedding_provider: str = "mock"
    openai_api_key: str | None = None
    openai_embedding_model: str = "text-embedding-3-small"
    local_embedding_model: str = "intfloat/multilingual-e5-small"
    embedding_dimensions: int = 1536
    telegram_bot_token: str | None = None
    telegram_chat_ids: str = ""
    notification_enabled: bool = False
    notification_time: str = "23:00"
    notification_timezone: str = "Asia/Seoul"
    notification_use_llm: bool = True
    openai_summary_model: str = "gpt-4o-mini"
    app_open_url: str = "https://calendar.jadendev.com/open"

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")


@lru_cache
def get_settings() -> Settings:
    return Settings()
