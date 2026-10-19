# ADR 0002 — Runner reports results from Surefire XML, not a JUnit listener

- Status: Accepted
- Date: 2026-10-19

## Context

Every test result a runner produces must reach Kafka (`test.results`, then `run.completed`). The tech doc (section 5, test-runner) left two options open:

1. A JUnit 5 listener inside the target's test JVM publishes each result to Kafka as it happens.
2. The build writes its usual JUnit XML reports, and a separate publisher sends them after the run.

The Phase 0 prototype (`benchmark/hunt.py`) used option 2 against real IDoFT repositories and showed:

- Every candidate repository with known order-dependent flaky tests uses **JUnit 4** (see ADR 0004). Surefire runs those through its JUnit 4 provider, which bypasses the JUnit Platform, so a JUnit 5 listener never sees them.
- Surefire's XML holds everything the aggregator needs: class, method, outcome, duration, failure message and stack trace. Parsing it needed no change to the target repository and about 15 lines of code.
- A full run of the test subset took seconds. A run of a whole suite takes minutes. Streaming each test result as it happens adds little over sending them all when the run ends.

## Decision

The runner reports results from Surefire's JUnit XML reports (Gradle's `test-results` XML later, which uses the same format).

- The runner container's entrypoint runs the build, then a small **publisher** in the same container parses `target/surefire-reports/TEST-*.xml` and publishes one `test.results` event per test case, then `run.completed`. There is no sidecar: the publisher runs after the build process exits, so nothing has to coordinate two containers.
- The publisher is a small Java program (Kafka client, same event schemas as the services), shipped in the runner base images (ADR 0003).
- If the build crashes before writing XML, the publisher still sends `run.completed` with the exit code and zero results, so the hunt never waits on a run that died.
- `runner/junit-extension` is not needed for v1. It is replaced by `runner/publisher` in Phase 1.

## Alternatives considered

- **JUnit 5 listener publishing to Kafka.** It allows live progress per test, but it only sees JUnit Platform tests, it has to be injected into a build we don't control, and it adds Kafka client dependencies to the target's test classpath, where they can clash.
- **XML plus a sidecar publisher.** Same data, but a second container has to detect when the first one finished. That is extra Kubernetes complexity for no gain.

## Consequences

- Works for JUnit 4 and JUnit 5 repositories without touching the target's build.
- The dashboard shows progress per run, not per test. That is acceptable, since runs are short.
- Results arrive in a burst when a run ends. The aggregator must already handle bursts from many pods, and its idempotency on `(runId, testId)` covers retries.
- A test that hangs leaves no XML. The run's `activeDeadlineSeconds` kills it, and the publisher reports the timeout in `run.completed`.
