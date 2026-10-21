"""Alembic runs this for `upgrade`. This service owns only the "agents" schema (tech doc section 7)."""

from alembic import context
from sqlalchemy import create_engine, text

with create_engine(context.config.get_main_option("sqlalchemy.url")).connect() as conn:
    # Alembic's own version table lives in the schema too, so the schema must exist first.
    conn.execute(text("CREATE SCHEMA IF NOT EXISTS agents"))
    conn.commit()
    context.configure(connection=conn, version_table_schema="agents")
    with context.begin_transaction():
        context.run_migrations()
