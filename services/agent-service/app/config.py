from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Read from FLAKEHUNTER_* env vars. Local defaults match deploy/compose."""

    model_config = SettingsConfigDict(env_prefix="FLAKEHUNTER_")

    database_url: str = "postgresql://flakehunter:flakehunter@localhost:5432/flakehunter"
    kafka_bootstrap_servers: str = "localhost:9092"

    # No key means a fake LLM that returns a canned plan: the pipeline runs, nothing is diagnosed.
    gemini_api_key: str | None = Field(default=None, validation_alias="GEMINI_API_KEY")
    # Free-tier limits (AI Studio, Oct 2026): 3.5 Flash Lite 15 RPM / 500 RPD; 3.8 Flash 5 RPM / 20 RPD. The Pro
    # models have no free quota.
    small_model: str = "gemini-3.5-flash-lite"  # triage, classifier (tech doc section 8)
    large_model: str = "gemini-3.8-flash"  # investigator, fix writer, critic

    # Tech doc section 8 guardrails: stop with a partial diagnosis once any cap is reached.
    tokens_per_finding: int = 100_000
    tokens_per_hunt: int = 500_000
    tokens_per_day: int = 2_000_000
