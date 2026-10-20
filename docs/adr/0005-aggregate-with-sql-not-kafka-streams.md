# ADR 0005 — result-aggregator scores hunts with SQL, not Kafka Streams

- Status: Accepted
- Date: 2026-10-20
- Amends: tech doc sections 2, 5 (result-aggregator) and 11

## Context

The tech doc planned Kafka Streams in result-aggregator: per-test counts kept in state stores, keyed by `huntId:testId`. But a hunt is scored once, after all its runs finish (section 9), not continuously. The results must also be stored in Postgres anyway (`test_results`), because Postgres is the system of record and the UI and agents read the evidence later.

Kafka Streams would add RocksDB state stores, changelog topics and a second copy of the same counts, on a laptop where memory goes to the runners first.

## Decision

- Plain `@KafkaListener`s store `test.results` (batched inserts), `run.completed` and the `hunt.status` events that matter (RUNNING with `plannedRuns`, ANALYZING) in the `results` schema. Every insert is idempotent on a natural key (`run_id, test_id`; `runs.id`; `hunts.id`), so no processed-events table is needed.
- A scheduled `Analyzer` picks hunts that are ready: ANALYZING seen, every planned run reported, and every SUCCEEDED run has all the results it announced. If a runner died without sending `run.completed`, the hunt is analyzed with the runs that reported, one minute after ANALYZING.
- Scoring is one `GROUP BY` over the hunt's results. Findings, `finding.detected` and `analysis.completed` are written in one transaction through an outbox.

## Consequences

- No state stores or changelog topics, and the counts can't drift from what Postgres holds.
- No live per-test statistics while a hunt is running; the UI shows run progress from `hunt.status` instead. If live statistics are needed, add Kafka Streams then.
- A hunt of 5,000 tests × 40 runs is 200,000 rows to group. Postgres does that in well under a second, so it's fine at local scale.
- Scaling out (Phase 2 KEDA) still works: listeners share partitions, and the analyzer locks the hunt row so only one replica scores it.
