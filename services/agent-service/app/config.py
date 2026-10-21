from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Read from FLAKEHUNTER_* env vars. Local defaults match deploy/compose."""

    model_config = SettingsConfigDict(env_prefix="FLAKEHUNTER_")

    database_url: str = "postgresql://flakehunter:flakehunter@localhost:5432/flakehunter"
    kafka_bootstrap_servers: str = "localhost:9092"

    # No key means a fake LLM that returns a canned plan: the pipeline runs, nothing is diagnosed.
    gemini_api_key: str | None = Field(default=None, validation_alias="GEMINI_API_KEY")
    small_model: str = "gemini-2.5-flash"  # triage, classifier (tech doc section 8)
    large_model: str = "gemini-2.5-pro"  # investigator, fix writer, critic

    # Tech doc section 8 guardrails: stop with a partial diagnosis once any cap is reached.
    tokens_per_finding: int = 100_000
    tokens_per_hunt: int = 500_000
    tokens_per_day: int = 2_000_000
