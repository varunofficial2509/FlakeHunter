-- Targeted runs an agent asked for to test one hypothesis (tech doc section 8). The id is the agent's
-- experimentId, so a repeated request is a no-op insert.
CREATE TABLE experiments (
    id          UUID PRIMARY KEY,
    finding_id  UUID        NOT NULL, -- no foreign key: findings live in result-aggregator's schema
    hunt_id     UUID        NOT NULL REFERENCES hunts (id),
    hypothesis  TEXT        NOT NULL,
    plan        JSONB       NOT NULL, -- the ExperimentRequested plan
    status      TEXT        NOT NULL, -- PREPARING | RUNNING | COMPLETED | REJECTED | FAILED
    reason      TEXT,                 -- why REJECTED or FAILED
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX experiments_finding ON experiments (finding_id);

ALTER TABLE runs
    ADD COLUMN experiment_id UUID REFERENCES experiments (id), -- null for the hunt's own runs
    ADD COLUMN test_filter   TEXT,  -- Surefire -Dtest
    ADD COLUMN failed_tests  JSONB; -- from run.completed: which tests failed in this run
CREATE INDEX runs_experiment ON runs (experiment_id) WHERE experiment_id IS NOT NULL;
