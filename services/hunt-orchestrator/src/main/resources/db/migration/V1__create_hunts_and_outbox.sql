CREATE TABLE hunts (
    id           UUID PRIMARY KEY,
    -- No foreign key: projects live in project-service's schema (tech doc section 7).
    project_id   UUID        NOT NULL,
    commit_sha   TEXT        NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    status       TEXT        NOT NULL,
    budget       JSONB       NOT NULL, -- {"maxRuns": 40, "maxParallelPods": 4}
    runner_image TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      INT         NOT NULL DEFAULT 0 -- optimistic locking
);

-- Events written in the same transaction as the state change; OutboxRelay publishes them.
CREATE TABLE outbox (
    id           UUID PRIMARY KEY, -- equals the envelope's eventId
    -- Publish order. created_at can't be used: now() is the same for every row in one transaction.
    seq          BIGINT GENERATED ALWAYS AS IDENTITY,
    topic        TEXT        NOT NULL,
    key          TEXT        NOT NULL,
    payload      JSONB       NOT NULL, -- the full event envelope
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

-- The relay only ever looks for unpublished rows, so index just those.
CREATE INDEX outbox_unpublished ON outbox (seq) WHERE published_at IS NULL;
