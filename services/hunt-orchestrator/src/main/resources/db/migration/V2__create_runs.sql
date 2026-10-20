-- Copied from project-service when the hunt starts, so a running hunt never depends on project-service
-- being up, and a later project edit doesn't change a hunt halfway (tech doc section 7: no cross-schema reads).
ALTER TABLE hunts
    ADD COLUMN repo_url       TEXT,
    ADD COLUMN build_tool     TEXT,
    ADD COLUMN jdk_version    INT,
    ADD COLUMN maven_args     TEXT,
    ADD COLUMN failure_reason TEXT; -- shown to the user when status is FAILED

CREATE TABLE runs (
    id          UUID PRIMARY KEY,
    hunt_id     UUID        NOT NULL REFERENCES hunts (id),
    kind        TEXT        NOT NULL DEFAULT 'HUNT', -- HUNT | EXPERIMENT | VERIFICATION (Phase 3)
    strategy    TEXT        NOT NULL,
    seed        BIGINT,               -- recorded so a failing order can be replayed exactly
    timezone    TEXT,                 -- TIMEZONE strategy only
    status      TEXT        NOT NULL, -- SCHEDULED | RUNNING | SUCCEEDED | FAILED | TIMED_OUT
    started_at  TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);
CREATE INDEX runs_hunt ON runs (hunt_id);

-- Kafka delivers at least once: an event id in here was already handled (tech doc section 6).
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
