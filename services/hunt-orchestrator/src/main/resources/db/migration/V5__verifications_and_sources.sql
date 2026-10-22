-- A verification is an experiment with a patch: the fix, run under the condition that made the test fail.
ALTER TABLE experiments
    ADD COLUMN patch   TEXT,   -- unified diff; null for an experiment
    ADD COLUMN sources JSONB;  -- from sources.exported: repo path → content
