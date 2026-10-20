-- What this service knows about each hunt, from hunt.status. No foreign keys to other schemas (tech doc section 7).
CREATE TABLE hunts (
    id               UUID PRIMARY KEY,
    planned_runs     INT,         -- from the RUNNING event
    runs_finished_at TIMESTAMPTZ, -- the orchestrator saw every run finish (ANALYZING)
    analyzed_at      TIMESTAMPTZ  -- findings computed and their events written
);

-- One row per run.completed. A run with no row here never reported (crashed runner) and is left out.
CREATE TABLE runs (
    id       UUID PRIMARY KEY,
    hunt_id  UUID NOT NULL,
    strategy TEXT NOT NULL,
    status   TEXT NOT NULL, -- SUCCEEDED | FAILED | TIMED_OUT
    total    INT  NOT NULL  -- test.results the runner sent before this event
);
CREATE INDEX runs_hunt ON runs (hunt_id);

CREATE TABLE test_results (
    run_id          UUID NOT NULL,
    test_id         TEXT NOT NULL,
    hunt_id         UUID NOT NULL,
    strategy        TEXT NOT NULL,
    seed            BIGINT,
    status          TEXT NOT NULL, -- PASSED | FAILED | ERROR | SKIPPED
    duration_ms     BIGINT NOT NULL,
    failure_message TEXT,
    stack_hash      TEXT,
    order_index     INT  NOT NULL,
    PRIMARY KEY (run_id, test_id) -- a redelivered result is a no-op insert
);
CREATE INDEX test_results_hunt ON test_results (hunt_id);

CREATE TABLE findings (
    id           UUID PRIMARY KEY,
    hunt_id      UUID        NOT NULL,
    test_id      TEXT        NOT NULL,
    score        NUMERIC     NOT NULL,
    failure_rate NUMERIC     NOT NULL,
    evidence     JSONB       NOT NULL, -- the finding.detected payload
    status       TEXT        NOT NULL DEFAULT 'DETECTED', -- later DIAGNOSING, DIAGNOSED, FIX_PROPOSED, VERIFIED, FIX_FAILED
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (hunt_id, test_id)
);

-- Same outbox as hunt-orchestrator's: events are written with the findings and published by OutboxRelay.
CREATE TABLE outbox (
    id           UUID PRIMARY KEY, -- equals the envelope's eventId
    seq          BIGINT GENERATED ALWAYS AS IDENTITY,
    topic        TEXT        NOT NULL,
    key          TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished ON outbox (seq) WHERE published_at IS NULL;
