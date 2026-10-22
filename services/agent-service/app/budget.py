import time
from uuid import uuid4

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage
from psycopg_pool import AsyncConnectionPool

from app.config import Settings
from app.tracing import tracer


class BudgetExceeded(Exception):
    pass


class Budget:
    """Token caps per finding, hunt and day, counted from the llm_calls table."""

    def __init__(self, pool: AsyncConnectionPool, settings: Settings):
        self.pool = pool
        self.settings = settings

    async def check(self, finding_id: str, hunt_id: str) -> None:
        async with self.pool.connection() as conn:
            cur = await conn.execute(
                """
                SELECT coalesce(sum(tokens_in + tokens_out) FILTER (WHERE finding_id = %(f)s), 0) AS finding,
                       coalesce(sum(tokens_in + tokens_out) FILTER (WHERE hunt_id = %(h)s), 0) AS hunt,
                       coalesce(sum(tokens_in + tokens_out)
                                FILTER (WHERE created_at >= date_trunc('day', now())), 0) AS day
                FROM llm_calls
                WHERE hunt_id = %(h)s OR created_at >= date_trunc('day', now())
                """,
                {"f": finding_id, "h": hunt_id},
            )
            used = await cur.fetchone()
        s = self.settings
        for scope, cap in (("finding", s.tokens_per_finding), ("hunt", s.tokens_per_hunt), ("day", s.tokens_per_day)):
            if used[scope] >= cap:
                raise BudgetExceeded(f"{scope} token budget used up: {used[scope]} of {cap}")

    async def call(
        self, llm: BaseChatModel, messages: list[BaseMessage], *, agent: str, finding_id: str, hunt_id: str
    ) -> AIMessage:
        """Checks the caps, calls the model and records its token usage."""
        await self.check(finding_id, hunt_id)
        model = getattr(llm, "model", type(llm).__name__)
        started = time.monotonic()
        with tracer.start_as_current_span(f"llm {agent}") as span:
            reply = await llm.ainvoke(messages)
            usage = reply.usage_metadata or {}  # fake models report none
            span.set_attributes(
                {
                    "gen_ai.request.model": model,
                    "gen_ai.usage.input_tokens": usage.get("input_tokens", 0),
                    "gen_ai.usage.output_tokens": usage.get("output_tokens", 0),
                    "flakehunter.finding_id": finding_id,
                }
            )
        async with self.pool.connection() as conn:
            await conn.execute(
                "INSERT INTO llm_calls (id, finding_id, hunt_id, agent, model, tokens_in, tokens_out, latency_ms)"
                " VALUES (%s, %s, %s, %s, %s, %s, %s, %s)",
                (
                    uuid4(),
                    finding_id,
                    hunt_id,
                    agent,
                    model,
                    usage.get("input_tokens", 0),
                    usage.get("output_tokens", 0),
                    int((time.monotonic() - started) * 1000),
                ),
            )
        return reply
