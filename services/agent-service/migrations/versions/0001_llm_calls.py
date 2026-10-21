"""llm_calls: one row per model call, for budgets and the LLM usage panel.

LangGraph creates its own checkpoint tables in this schema (AsyncPostgresSaver.setup).

Revision ID: 0001
"""

from alembic import op

revision = "0001"
down_revision = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE agents.llm_calls (
            id          UUID PRIMARY KEY,
            finding_id  UUID        NOT NULL,
            hunt_id     UUID        NOT NULL,
            agent       TEXT        NOT NULL,
            model       TEXT        NOT NULL,
            tokens_in   INT         NOT NULL,
            tokens_out  INT         NOT NULL,
            latency_ms  INT         NOT NULL,
            created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
        );
        CREATE INDEX llm_calls_hunt ON agents.llm_calls (hunt_id);
        CREATE INDEX llm_calls_created ON agents.llm_calls (created_at);
    """)
