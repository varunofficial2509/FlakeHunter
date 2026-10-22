-- The W3C trace context of the transaction that added the event, so the relay sends it inside the same trace.
ALTER TABLE outbox ADD COLUMN traceparent TEXT;
