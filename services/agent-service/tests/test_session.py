"""Agent sessions against a real Postgres (Testcontainers) with fake LLMs and a fake Kafka producer."""

import json
from pathlib import Path
from uuid import NAMESPACE_URL, uuid4, uuid5

import pytest
from langchain_core.language_models.fake_chat_models import FakeListChatModel
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from testcontainers.community.postgres import PostgresContainer

from app.budget import Budget
from app.config import Settings
from app.consumer import EXPERIMENT_COMPLETED, FINDING_DETECTED, FIX_VERIFIED, handle, thread
from app.graph import build_graph
from app.llm import FAKE_PLAN
from app.main import migrate, open_pool

IMAGES = Path(__file__).parents[3] / "deploy/images.env"
POSTGRES_IMAGE = next(
    line.split("=", 1)[1] for line in IMAGES.read_text().splitlines() if line.startswith("POSTGRES_IMAGE=")
)
TEST = "com.acme.DateTest#indian"
SOURCE = "src/test/java/com/acme/DateTest.java"
CODE = "class DateTest {\n    TimeZone zone = TimeZone.getDefault();\n}\n"


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


class Published(list):
    async def __call__(self, topic, key, hunt_id, payload):
        self.append((topic, key, hunt_id, payload))


def envelope() -> dict:
    finding = {"findingId": str(uuid4()), "testId": TEST, "failures": 11, "runs": 12}
    return {"eventType": FINDING_DETECTED, "huntId": str(uuid4()), "payload": finding}


def first_experiment_id(env) -> str:
    return str(uuid5(NAMESPACE_URL, f"flakehunter:{env['payload']['findingId']}:1"))


def experiment() -> str:
    plan = {"tests": [TEST], "strategy": "TIMEZONE", "timezone": "Asia/Kolkata", "repeat": 10}
    return json.dumps({"action": "experiment", "hypothesis": "fails in IST", "plan": plan})


def conclusion(confirmed: bool, evidence: list[str]) -> str:
    return json.dumps(
        {
            "action": "conclude",
            "hypothesis": "fails in IST",
            "category": "TIME_DEPENDENT",
            "confirmed": confirmed,
            "evidence": evidence,
            "explanation": "date math uses the default zone",
        }
    )


def completed(env, experiment_id, failures, topic=EXPERIMENT_COMPLETED, runs=10) -> dict:
    payload = {
        "findingId": env["payload"]["findingId"],
        "experimentId": experiment_id,
        "status": "COMPLETED",
        "reason": None,
        "runs": runs,
        "tests": [{"test": TEST, "failures": failures}],
        "sources": {SOURCE: CODE} if topic == EXPERIMENT_COMPLETED else None,
    }
    return {"eventType": topic, "huntId": env["huntId"], "payload": payload}


def classification(evidence: list[str]) -> str:
    return json.dumps({"category": "TIME_DEPENDENT", "confidence": 0.9, "evidence": evidence})


def fix(replace: str, search: str = "TimeZone.getDefault()") -> str:
    edit = {"path": SOURCE, "search": search, "replace": replace}
    return json.dumps({"edits": [edit], "rationale": "pin the zone the test expects"})


def review(approved: bool) -> str:
    return json.dumps({"approved": approved, "feedback": "removes the dependency on the default zone"})


async def waiting_for(graph, env, node) -> dict:
    state = await graph.aget_state(thread(env["payload"]["findingId"]))
    assert state.next == (node,)
    return state.values["pending"]


def make_graph(pool, large_answers, small_answers=(FAKE_PLAN,), published=None, **caps):
    return build_graph(
        FakeListChatModel(responses=list(small_answers)),
        FakeListChatModel(responses=large_answers),
        Budget(pool, Settings(**caps)),
        AsyncPostgresSaver(pool),
        published if published is not None else Published(),
    )


async def calls(pool, env) -> int:
    async with pool.connection() as conn:
        cur = await conn.execute("SELECT count(*) AS n FROM llm_calls WHERE hunt_id = %s", (env["huntId"],))
        return (await cur.fetchone())["n"]


async def test_requests_an_experiment_then_fixes_and_verifies(pool):
    env = envelope()
    experiment_id = first_experiment_id(env)
    published = Published()
    graph = make_graph(
        pool,
        [experiment(), conclusion(True, [experiment_id]), fix('TimeZone.getTimeZone("Asia/Kolkata")'), review(True)],
        small_answers=[FAKE_PLAN, classification([experiment_id])],
        published=published,
    )

    await handle(graph, FINDING_DETECTED, env)
    state = await graph.aget_state(thread(env["payload"]["findingId"]))
    assert state.next == ("experiment",)  # paused, checkpointed, waiting for the orchestrator
    topic, key, hunt_id, request = published[0]
    assert (topic, key, hunt_id) == ("experiment.requested", env["payload"]["findingId"], env["huntId"])
    assert request["experimentId"] == experiment_id and request["plan"]["timezone"] == "Asia/Kolkata"

    await handle(graph, FINDING_DETECTED, env)  # redelivered while waiting: republishes the same request
    assert [p[3]["experimentId"] for p in published] == [experiment_id, experiment_id]
    await handle(graph, EXPERIMENT_COMPLETED, completed(env, str(uuid4()), 3))  # not the one it waits for
    assert (await graph.aget_state(thread(env["payload"]["findingId"]))).next == ("experiment",)

    await handle(graph, EXPERIMENT_COMPLETED, completed(env, experiment_id, 7))
    await handle(graph, EXPERIMENT_COMPLETED, completed(env, experiment_id, 7))  # duplicate answer

    verification = await waiting_for(graph, env, "verify")
    topic, _, _, request = published[-1]
    assert topic == "verification.requested" and request["experimentId"] == verification["experimentId"]
    # The condition that made it fail, 100 times, with the fix applied.
    assert request["plan"] == {"tests": [TEST], "strategy": "TIMEZONE", "timezone": "Asia/Kolkata", "repeat": 100}
    assert '+    TimeZone zone = TimeZone.getTimeZone("Asia/Kolkata");' in request["patch"]

    await handle(graph, FIX_VERIFIED, completed(env, verification["experimentId"], 0, FIX_VERIFIED, runs=100))

    state = await graph.aget_state(thread(env["payload"]["findingId"]))
    assert state.values["status"] == "FIX_VERIFIED"
    topic, _, _, diagnosis = published[-1]  # tells the orchestrator this finding is done
    assert topic == "diagnosis.completed"
    assert diagnosis["status"] == "FIX_VERIFIED" and diagnosis["category"] == "TIME_DEPENDENT"
    assert diagnosis["patch"] == request["patch"]
    assert state.values["classification"]["category"] == "TIME_DEPENDENT"
    assert state.values["sources"] == {SOURCE: CODE}
    assert "sources" not in state.values["experiments"][0]["result"]  # kept once, not per experiment
    assert state.values["experiments"][0]["result"]["tests"][0]["failures"] == 7
    assert [s["agent"] for s in state.values["steps"]] == [
        *["triage", "investigator", "investigator", "investigator"],
        *["classifier", "fix writer", "critic", "verifier"],
    ]
    assert await calls(pool, env) == 6  # resuming re-ran no LLM call


async def test_a_hidden_failure_is_refused_and_a_failed_verification_gets_one_more_round(pool):
    env = envelope()
    experiment_id = first_experiment_id(env)
    graph = make_graph(
        pool,
        [
            experiment(),
            conclusion(True, [experiment_id]),
            fix("TimeZone.getDefault(); // @Disabled", search="TimeZone.getDefault();"),  # refused, no critic call
            fix('TimeZone.getTimeZone("UTC")'),
            review(True),
            fix('TimeZone.getTimeZone("IST")'),
            review(True),
        ],
        small_answers=[FAKE_PLAN, classification([experiment_id])],
    )
    await handle(graph, FINDING_DETECTED, env)
    await handle(graph, EXPERIMENT_COMPLETED, completed(env, experiment_id, 7))

    for failures in (3, 1):
        verification = await waiting_for(graph, env, "verify")
        await handle(graph, FIX_VERIFIED, completed(env, verification["experimentId"], failures, FIX_VERIFIED, 100))

    state = await graph.aget_state(thread(env["payload"]["findingId"]))
    assert state.values["status"] == "FIX_FAILED"
    attempts = state.values["attempts"]
    assert attempts[0]["rejected"].startswith("The fix disables the test")
    assert [a.get("verification") for a in attempts[1:]] == [
        "The test still failed 3 times in 100 runs",
        "The test still failed 1 times in 100 runs",
    ]
    assert await calls(pool, env) == 9  # triage, 2 investigator, classifier, 3 fixes; 2 critics (not the refusal)


async def test_stops_when_budget_is_used_up(pool):
    env = envelope()
    graph = make_graph(pool, [experiment()], tokens_per_finding=0)

    await handle(graph, FINDING_DETECTED, env)

    state = await graph.aget_state(thread(env["payload"]["findingId"]))
    assert state.values["status"] == "BUDGET_EXCEEDED"
    assert await calls(pool, env) == 0


async def test_retries_invalid_json_once(pool):
    env = envelope()
    graph = make_graph(pool, [conclusion(False, [])], small_answers=["not json", FAKE_PLAN])

    await handle(graph, FINDING_DETECTED, env)

    state = await graph.aget_state(thread(env["payload"]["findingId"]))
    assert state.values["status"] == "UNKNOWN"
    assert await calls(pool, env) == 3


async def test_a_conclusion_must_cite_experiments_that_ran(pool):
    made_up = conclusion(True, [str(uuid4())])
    graph = make_graph(pool, [made_up, made_up])

    with pytest.raises(ValueError, match="did not run"):  # the consumer retries, then dead-letters
        await handle(graph, FINDING_DETECTED, envelope())
