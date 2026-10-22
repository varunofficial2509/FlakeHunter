-- Read model of the agents' work per finding (tech doc section 5): the latest snapshot of its session, from
-- diagnosis.updated while it runs and diagnosis.completed once it ends.
CREATE TABLE diagnoses (
    finding_id UUID PRIMARY KEY,
    status     TEXT,                -- null while the session runs
    snapshot   JSONB       NOT NULL, -- the DiagnosisCompleted payload: category, patch, steps, ...
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
