import json
import logging

from langchain_core.language_models import BaseChatModel
from langchain_core.language_models.fake_chat_models import FakeListChatModel

from app.config import Settings

log = logging.getLogger(__name__)

FAKE_PLAN = json.dumps(
    {
        "categories": ["UNKNOWN"],
        "hypotheses": [
            {
                "statement": "Fake LLM: set GEMINI_API_KEY for a real diagnosis",
                "category": "UNKNOWN",
                "experiment": "none",
            }
        ],
    }
)


def make_llm(settings: Settings, tier: str) -> BaseChatModel:
    """tier is "small" or "large" (tech doc section 8, model routing)."""
    if not settings.gemini_api_key:
        log.warning("GEMINI_API_KEY is not set: using a fake LLM")
        return FakeListChatModel(responses=[FAKE_PLAN])
    from langchain_google_genai import ChatGoogleGenerativeAI

    return ChatGoogleGenerativeAI(
        model=settings.small_model if tier == "small" else settings.large_model,
        google_api_key=settings.gemini_api_key,
        temperature=0,
        response_mime_type="application/json",  # every agent answers in JSON, validated with Pydantic
    )
