import asyncio
import logging
from contextlib import asynccontextmanager
from pathlib import Path

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from alembic import command
from alembic.config import Config
from fastapi import FastAPI, Response
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from app.budget import Budget
from app.config import Settings
from app.consumer import EXPERIMENT_COMPLETED, FINDING_DETECTED, consume, publisher
from app.graph import build_graph
from app.llm import make_llm

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")


def migrate(database_url: str) -> None:
    cfg = Config()
    cfg.set_main_option("script_location", str(Path(__file__).parent.parent / "migrations"))
    cfg.set_main_option("sqlalchemy.url", database_url.replace("postgresql://", "postgresql+psycopg://", 1))
    command.upgrade(cfg, "head")


def open_pool(database_url: str) -> AsyncConnectionPool:
    # The checkpointer needs autocommit and dict rows; search_path puts its tables in this service's schema.
    kwargs = {"autocommit": True, "prepare_threshold": 0, "row_factory": dict_row, "options": "-c search_path=agents"}
    return AsyncConnectionPool(database_url, kwargs=kwargs, max_size=4, open=False)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = Settings()
    await asyncio.to_thread(migrate, settings.database_url)
    async with open_pool(settings.database_url) as pool:
        checkpointer = AsyncPostgresSaver(pool)
        await checkpointer.setup()
        producer = AIOKafkaProducer(
            bootstrap_servers=settings.kafka_bootstrap_servers, acks="all", enable_idempotence=True
        )
        graph = build_graph(
            make_llm(settings, "small"),
            make_llm(settings, "large"),
            Budget(pool, settings),
            checkpointer,
            publisher(producer),
        )
        consumer = AIOKafkaConsumer(
            FINDING_DETECTED,
            EXPERIMENT_COMPLETED,
            bootstrap_servers=settings.kafka_bootstrap_servers,
            group_id="agents",
            enable_auto_commit=False,
            auto_offset_reset="earliest",  # findings sent while the service was scaled to zero still count
        )
        await producer.start()
        await consumer.start()
        app.state.consumer = asyncio.create_task(consume(graph, consumer, producer))
        try:
            yield
        finally:
            # Cancelling mid-session leaves its offset uncommitted: the next pod resumes it from the checkpoint.
            app.state.consumer.cancel()
            await consumer.stop()
            await producer.stop()


app = FastAPI(title="agent-service", lifespan=lifespan)


@app.get("/health")
async def health(response: Response) -> dict[str, str]:
    if app.state.consumer.done():  # the consumer loop died: let Kubernetes restart the pod
        response.status_code = 503
        return {"status": "DOWN"}
    return {"status": "UP"}
