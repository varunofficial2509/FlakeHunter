-- Never updated past DETECTED: the agents' progress lives in diagnoses.
ALTER TABLE findings DROP COLUMN status;
