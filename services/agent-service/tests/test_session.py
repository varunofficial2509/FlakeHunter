"""Agent sessions against a real Postgres (Testcontainers) with a fake LLM."""

import json
from pathlib import Path
from uuid import uuid4

import pytest
from langchain_core.language_models.fake_chat_models import FakeListChatModel
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from testcontainers.community.postgres import PostgresContainer

from app.budget import Budget
from app.config import Settings
from app.consumer import handle
from app.graph import build_graph
from app.llm import FAKE_PLAN
from app.main import migrate, open_pool

IMAGES = Path(__file__).parents[3] / "deploy/images.env"
POSTGRES_IMAGE = next(
    line.split("=", 1)[1] for line in IMAGES.read_text().splitlines() if line.startswith("POSTGRES_IMAGE=")
)


@pytest.fixture(scope="module")
def database_url():
    with PostgresContainer(POSTGRES_IMAGE, driver=None) as pg:
        url = pg.get_connection_url()
        migrate(url)
        yield url


@pytest.fixture
async def pool(database_url):
    async with open_pool(database_url) as pool:
        await AsyncPostgresSaver(pool).setup()
        yield pool


def envelope() -> dict:
    finding = {"findingId": str(uuid4()), "testId": "com.acme.DateTest#indian", "failures": 11, "runs": 12}
    return {"eventType": "finding.detected", "huntId": str(uuid4()), "payload": finding}


def make_graph(pool, llm, **caps):
    return build_graph(llm, Budget(pool, Settings(**caps)), AsyncPostgresSaver(pool))


async def calls(pool, env) -> int:
    async with pool.connection() as conn:
        cur = await conn.execute("SELECT count(*) AS n FROM llm_calls WHERE hunt_id = %s", (env["huntId"],))
        return (await cur.fetchone())["n"]


async def test_triages_once_and_ignores_redelivery(pool):
    graph = make_graph(pool, FakeListChatModel(responses=[FAKE_PLAN]))
    env = envelope()

    await handle(graph, env)
    await handle(graph, env)  # Kafka redelivered the same event

    state = await graph.aget_state({"configurable": {"thread_id": env["payload"]["findingId"]}})
    assert state.values["status"] == "TRIAGED"
    assert state.values["plan"]["categories"] == ["UNKNOWN"]
    assert len(state.values["steps"]) == 1
    assert await calls(pool, env) == 1


async def test_stops_when_budget_is_used_up(pool):
    graph = make_graph(pool, FakeListChatModel(responses=[FAKE_PLAN]), tokens_per_finding=0)
    env = envelope()

    await handle(graph, env)

    state = await graph.aget_state({"configurable": {"thread_id": env["payload"]["findingId"]}})
    assert state.values["status"] == "BUDGET_EXCEEDED"
    assert await calls(pool, env) == 0


async def test_retries_invalid_json_once(pool):
    graph = make_graph(pool, FakeListChatModel(responses=["not json", FAKE_PLAN]))
    env = envelope()

    await handle(graph, env)

    state = await graph.aget_state({"configurable": {"thread_id": env["payload"]["findingId"]}})
    assert state.values["status"] == "TRIAGED"
    assert await calls(pool, env) == 2


async def test_gives_up_after_second_invalid_answer(pool):
    graph = make_graph(pool, FakeListChatModel(responses=["not json", json.dumps({"categories": []})]))

    with pytest.raises(ValueError):  # the consumer retries, then dead-letters
        await handle(graph, envelope())
