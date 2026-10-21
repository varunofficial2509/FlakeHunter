import json
import logging
from typing import Any

from langchain_core.language_models import BaseChatModel
from langchain_core.language_models.fake_chat_models import FakeListChatModel
from langchain_core.messages import AIMessage, BaseMessage
from langchain_core.outputs import ChatGeneration, ChatResult

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


class FakeInvestigator(BaseChatModel):
    """No key: asks for one small experiment on the finding's test, then concludes from its result. Lets the
    whole experiment round trip run in the cluster without an API key."""

    @property
    def _llm_type(self) -> str:
        return "fake-investigator"

    def _generate(self, messages: list[BaseMessage], stop: Any = None, run_manager: Any = None, **kw: Any):
        data = json.loads(messages[1].content.split("<data>", 1)[1].rsplit("</data>", 1)[0])
        finding, done = data["finding"], data["experiments"]
        if not done:
            strategies = [s for s in finding.get("triggeringStrategies", []) if s in ("SHUFFLE_ORDER", "PARALLEL")]
            answer = {
                "action": "experiment",
                "hypothesis": "Fake LLM: does it fail on its own?",
                "plan": {
                    "tests": [finding["testId"]],
                    "strategy": (strategies or ["BASELINE"])[0],
                    "timezone": None,
                    "repeat": 5,
                },
            }
        else:
            result = done[-1]["result"]
            failed = result["status"] == "COMPLETED" and any(t["failures"] for t in result["tests"])
            answer = {
                "action": "conclude",
                "hypothesis": done[-1]["hypothesis"],
                "category": "UNKNOWN",
                "confirmed": failed,
                "evidence": [done[-1]["experimentId"]] if failed else [],
                "explanation": "Fake LLM: set GEMINI_API_KEY for a real diagnosis",
            }
        return ChatResult(generations=[ChatGeneration(message=AIMessage(json.dumps(answer)))])


def make_llm(settings: Settings, tier: str) -> BaseChatModel:
    """tier is "small" or "large" (tech doc section 8, model routing)."""
    if not settings.gemini_api_key:
        log.warning("GEMINI_API_KEY is not set: using a fake %s LLM", tier)
        return FakeListChatModel(responses=[FAKE_PLAN]) if tier == "small" else FakeInvestigator()
    from langchain_google_genai import ChatGoogleGenerativeAI

    return ChatGoogleGenerativeAI(
        model=settings.small_model if tier == "small" else settings.large_model,
        google_api_key=settings.gemini_api_key,
        temperature=0,
        response_mime_type="application/json",  # every agent answers in JSON, validated with Pydantic
    )
