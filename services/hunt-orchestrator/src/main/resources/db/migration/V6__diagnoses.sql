-- A hunt with findings stays DIAGNOSING until every finding's agent session reported diagnosis.completed.
ALTER TABLE hunts ADD COLUMN findings INT; -- from analysis.completed
CREATE TABLE diagnoses (
    finding_id UUID PRIMARY KEY, -- one per finding: a redelivered diagnosis.completed is a no-op insert
    hunt_id    UUID        NOT NULL REFERENCES hunts (id),
    status     TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX diagnoses_hunt ON diagnoses (hunt_id);
