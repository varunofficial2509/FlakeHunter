# FlakeHunter — Technical Documentation

Oct 17, 2026 · @Varun

## 1. Overview

FlakeHunter finds flaky tests in a Java repository by rerunning its test suite many times in parallel Kubernetes pods, then uses a team of AI agents to prove the root cause and verify a fix. It is a solo, base-to-production personal project built for learning and as a resume showcase.

### Problem

A flaky test passes and fails on the same code. Flaky tests waste CI time, train developers to ignore red builds, and hide real bugs. Most teams detect them by accident and quarantine them without ever finding why they fail.

### What FlakeHunter does

1. A user connects a GitHub repository (public URL first, GitHub App later).
2. A **hunt** reruns the test suite N times in parallel runner pods, varying test order, random seed, parallelism, CPU limits and timezone.
3. Results stream through Kafka. A statistical aggregator flags tests with inconsistent outcomes.
4. AI agents investigate each flaky test. They read code, stack traces and pass-vs-fail log diffs, and can **order extra experiments** (for example: run this test alone 50 times, or right after test X).
5. The root cause is classified, a fix is proposed, and the fix is **verified by rerunning it many times**.
6. Everything is visible live on a React dashboard, and optionally posted back to GitHub.

### Goals

- Build a real distributed system: Java microservices, Python agent service, React UI, Kafka, Postgres, Docker, Kubernetes.
- Demonstrate scaling for real reasons: runner pods as Kubernetes Jobs, agent workers scaled on Kafka lag.
- Demonstrate graceful degradation: an **AI kill switch** scales the agent service to zero; detection keeps working and diagnoses resume when it comes back.
- Produce provable output: a benchmark number against known flaky tests, and fixes verified by reruns.
- Deploy to the cloud with Helm and CI/CD, with a recorded demo.

### Non-goals

- Languages other than Java (Maven + JUnit 4 or 5) in v1. See ADR 0004.
- Multi-tenant SaaS billing, teams or SSO.
- Replacing CI. FlakeHunter runs hunts on demand or on PR, beside CI.
- Auto-merging fixes. A human always approves.

### What is different from existing tools

Existing flaky-test tools mostly detect, track and quarantine. FlakeHunter's distinct part is the agent loop: agents that design and run their own experiments on Kubernetes to prove a root cause, then verify the fix statistically.

### Working rules for this project

- Every service boundary must have a reason (scaling, language, or failure isolation) that can be defended in an interview.
- Detection must work with AI turned off. AI adds diagnosis and fixes on top.
- No invented results. Every claim in the README or a demo comes from a real run.
- It is presented honestly as a personal project.

## 2. Tech stack and constraints

Java owns the core platform, Python owns only the AI agents, and everything runs in Docker on Kubernetes. Versions are targets; pin exact versions in Phase 0.

| Area              | Choice                                                                  | Why                                                       |
| ----------------- | ----------------------------------------------------------------------- | --------------------------------------------------------- |
| Core services     | Java 21, Spring Boot 3.x, Spring Cloud Gateway                          | Target-role stack; virtual threads for I/O-heavy services |
| Stream processing | Plain Kafka consumers + SQL in result-aggregator (ADR 0005)             | Hunts are scored once at the end; Postgres holds the data |
| Agent service     | Python 3.12, FastAPI, LangGraph                                         | Best agent tooling; a real reason for a polyglot boundary |
| LLM               | Hosted APIs (Gemini / OpenAI), routed by task                           | Local models do not fit beside the cluster on 16 GB       |
| Frontend          | React 18, TypeScript, Vite, TanStack Query                              | Live dashboard over SSE                                   |
| Messaging         | Apache Kafka (KRaft, no ZooKeeper)                                      | Durable async events, replay, consumer lag for scaling    |
| Database          | PostgreSQL 16 (+ pgvector later, optional)                              | Relational state, outbox table                            |
| Cache / locks     | Redis (optional, Phase 3+)                                              | Rate limits on LLM calls, short-lived locks               |
| Code-to-K8s       | fabric8 Kubernetes client (Java)                                        | Orchestrator creates Jobs and scales Deployments          |
| Containers        | Docker, multi-stage builds, ARM64 + AMD64 images                        | Mac is ARM; cloud nodes may be AMD64                      |
| Local Kubernetes  | k3d or OrbStack Kubernetes                                              | Lighter than minikube on a MacBook Air                    |
| Autoscaling       | KEDA (Kafka scaler)                                                     | Scale on consumer lag, down to zero                       |
| Packaging         | Helm charts                                                             | One chart per service + an umbrella chart                 |
| CI/CD             | GitHub Actions; Argo CD optional                                        | Build, test, push images, deploy                          |
| Observability     | OpenTelemetry, Prometheus, Grafana, Loki or plain logs, Tempo or Jaeger | Traces across Java, Kafka and Python                      |
| Cloud             | Azure (AKS or a VM running k3s)                                         | Matches learning goal; tear down after demos              |
| Tests             | JUnit 5, Testcontainers, pytest, Playwright (light)                     | Integration tests against real Kafka and Postgres         |

### Constraints

- **Hardware:** MacBook Air M4, 16 GB RAM, fanless. Long builds and load tests throttle, so keep local fan-out to 4–8 runner pods.
- **Local footprint budget:** one Kafka broker, one Postgres, one replica per service, agent service scaled to zero when idle.
- **Solo developer:** about 10–12 hours a week, beside a full-time job.
- **Cost:** LLM spend capped per hunt and per day; cloud cluster only for demos, then torn down.
- **Target repos:** small-to-medium Java projects whose test suites finish in a few minutes.

## 3. Core concepts and domain model

A **hunt** is the unit of work: one repository at one commit, rerun many times, producing flaky-test findings that agents then diagnose and fix.

| Term            | Meaning                                                                                                                |
| --------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Project         | A connected repository: URL, default branch, build tool, test command, settings                                        |
| Hunt            | One investigation of a project at a specific commit SHA, with a budget (runs, pods, LLM tokens)                        |
| Strategy        | How a run is perturbed: `BASELINE`, `SHUFFLE_ORDER`, `RANDOM_SEED`, `PARALLEL`, `CPU_THROTTLE`, `TIMEZONE`, `ISOLATED` |
| Run             | One execution of the suite (or a subset) in one runner pod with one strategy and seed                                  |
| Test case       | A test identified by `class#method`, stable across runs                                                                |
| Test result     | Outcome of one test in one run: `PASSED`, `FAILED`, `ERROR`, `SKIPPED`, duration, failure message, stack trace hash    |
| Flakiness score | Statistical estimate that a test is flaky, from its outcomes across runs and strategies                                |
| Finding         | A test confirmed as flaky in a hunt, with evidence (failure rate, which strategies trigger it)                         |
| Experiment      | A targeted run requested by an agent to test a hypothesis (for example: run test A after test B, 50 times)             |
| Diagnosis       | The agents' conclusion: root-cause category, explanation, evidence, confidence                                         |
| Fix proposal    | A code patch (unified diff) plus rationale                                                                             |
| Verification    | Reruns of the patched code; the fix passes only if the test passes every time over the required count                  |

### Root-cause categories

`ORDER_DEPENDENCY` · `SHARED_STATE` · `ASYNC_WAIT` (race, sleep-based timing) · `TIME_DEPENDENT` (clock, timezone, dates) · `RANDOMNESS` (unseeded random) · `CONCURRENCY` · `RESOURCE_LEAK` (ports, files, threads) · `NETWORK` (external calls) · `UNORDERED_COLLECTION` (HashMap/HashSet iteration order) · `UNKNOWN`

### Hunt lifecycle

`CREATED` → `PREPARING` (clone, resolve dependencies, compile tests once) → `RUNNING` (baseline + perturbed runs) → `ANALYZING` (aggregate, detect) → `DIAGNOSING` (agents, only if AI is on) → `VERIFYING` → `COMPLETED`

Side states: `FAILED` (unrecoverable error), `CANCELLED` (by user), `AWAITING_AI` (findings ready, AI switched off). A hunt in `AWAITING_AI` moves to `DIAGNOSING` automatically when AI comes back.

## 4. System architecture

Synchronous traffic enters only through the gateway; services coordinate through Kafka events, and each service owns its own Postgres schema.

&#91;embedded content: FlakeHunter architecture · 6 services + runner Jobs\]

Runner pods are created by the orchestrator through the Kubernetes API and talk only to Kafka; the agent service can disappear entirely without breaking detection.

### One hunt, end to end

1. User starts a hunt in the web UI → gateway → hunt-orchestrator (`POST /api/hunts`).
2. Orchestrator prepares the workspace (clone + compile once), plans runs, writes them to Postgres with outbox events, and creates runner Jobs.
3. Runner pods execute tests and publish `test.results` and `run.completed`.
4. result-aggregator scores tests; when all runs finish it publishes `finding.detected` for each flaky test and `analysis.completed`.
5. agent-service consumes findings, requests experiments (`experiment.requested`), which the orchestrator runs as more Jobs.
6. Agents publish diagnosis and fix; orchestrator runs verification Jobs and publishes `fix.verified`.
7. Gateway streams hunt progress to the UI over SSE; project-service optionally comments on the GitHub PR.

## 5. Service specifications

Six deployable services plus a short-lived runner image. Each service owns its own tables and talks to others through Kafka; synchronous REST is used only from the gateway inward and for a few queries.

| Service                 | Language                    | Owns                                             | Scales on                                     | Boundary reason                         |
| ----------------------- | --------------------------- | ------------------------------------------------ | --------------------------------------------- | --------------------------------------- |
| api-gateway             | Java (Spring Cloud Gateway) | Routing, auth, rate limits, SSE fan-out          | Requests                                      | Single entry point                      |
| project-service         | Java                        | Projects, settings, GitHub webhooks, PR comments | Requests                                      | Integration and config, low load        |
| hunt-orchestrator       | Java                        | Hunts, runs, experiments, K8s Jobs, AI switch    | Hunts in flight                               | Owns the saga and the K8s API           |
| result-aggregator       | Java                        | Test results, flakiness scores, findings         | Kafka lag on `test.results`                   | High-volume stream, different scaling   |
| agent-service           | Python                      | Diagnoses, fix proposals, LLM usage              | Kafka lag on `finding.detected` (KEDA, min 0) | Different language, can be switched off |
| web                     | React (static, Nginx)       | UI                                               | Requests                                      | Frontend                                |
| test-runner (Job image) | Java + Maven + publisher    | Nothing persistent                               | One pod per run                               | Isolation of untrusted code             |

### api-gateway

- Routes `/api/projects/**` → project-service, `/api/hunts/**` → hunt-orchestrator, `/api/findings/**` → result-aggregator, `/api/admin/**` → hunt-orchestrator.
- Auth: single-user JWT login in v1 (admin user from a secret); GitHub OAuth optional later.
- Rate limit with Redis or in-memory token bucket.
- `GET /api/hunts/{id}/stream` (SSE): live hunt progress, proxied from hunt-orchestrator.

### project-service

- `POST /api/projects` — register repo URL, branch, build tool, test command.
- `GET /api/projects`, `GET /api/projects/{id}`, `PATCH /api/projects/{id}`.
- `POST /api/github/webhook` — verifies signature; on PR events publishes `hunt.requested` if auto-hunt is on.
- Consumes `fix.verified` → posts a PR comment or opens a PR (Phase 4).

### hunt-orchestrator

- `POST /api/hunts` — start a hunt `{projectId, commitSha?, budget}`; returns hunt id. Also consumes `hunt.requested`.
- `GET /api/hunts/{id}`, `POST /api/hunts/{id}/cancel`.
- `GET /api/admin/ai` / `PUT /api/admin/ai {enabled}` — the AI kill switch (section 8).
- Prepares the hunt: picks the JDK base image, clones the repo at the SHA, resolves dependencies and runs `test-compile` once into a workspace that every run reuses (ADR 0003).
- Plans runs from strategies and budget, creates one Kubernetes Job per run (fabric8), enforces max parallel pods.
- Consumes `experiment.requested` from agents, validates it against the budget, runs it as Jobs, publishes `experiment.completed`.
- Consumes `run.completed` and `analysis.completed` to advance the hunt state machine (saga). Writes state changes and events in one transaction via the outbox.
- Publishes hunt progress for the SSE stream.

### result-aggregator

- Consumes `test.results` (high volume), idempotent on `(runId, testId)`.
- Stores results in Postgres; counts outcomes per strategy and failure clusters with SQL (ADR 0005).
- When all runs of a hunt complete, computes flakiness scores (section 9) and publishes `finding.detected` per flaky test, then `analysis.completed`.
- `GET /api/findings?huntId=`, `GET /api/findings/{id}` (with evidence, diagnosis, fix, verification once available).
- Consumes `diagnosis.completed`, `fix.proposed`, `fix.verified` to keep the finding view complete (CQRS-style read model).

### agent-service

- Consumes `finding.detected` (consumer group `agents`).
- Runs the agent graph (section 8); tools call back to the platform through Kafka (`experiment.requested`) and read-only REST (source files, logs).
- Publishes `diagnosis.completed`, `fix.proposed`. Requests verification with `verification.requested`.
- Enforces per-hunt and per-day token budgets; records LLM usage.
- Stateless pods; graph state checkpointed in Postgres so a killed pod resumes.

### test-runner (Kubernetes Job)

- One base image per JDK major version (8, 11, 17, 21): Temurin, Maven 3.9.16 and the publisher. Nothing is built per commit (ADR 0003).
- Mounts the hunt's prepared workspace and dependency cache, and only runs tests.
- Inputs via env: `HUNT_ID`, `RUN_ID`, `STRATEGY`, `SEED`, `TEST_FILTER`, `REPEAT`, `TZ`.
- Applies the strategy: runs Surefire 3.x explicitly with `-Dsurefire.runOrder=random` and the seed, plus parallel config, `TZ`, and CPU limits set on the Job.
- After the build exits, the publisher in the same container parses Surefire's JUnit XML, publishes one `test.results` event per test, then `run.completed` with exit status, duration and totals. If the build crashed, `run.completed` still goes out with zero results (ADR 0002).
- No network except Kafka and the Maven proxy; `activeDeadlineSeconds` and `ttlSecondsAfterFinished` set.

### web

- Pages: Projects, Hunt detail (live run grid, pod count, progress), Findings list, Finding detail (evidence charts, agent reasoning timeline, diff, verification), Admin (AI switch, budgets, LLM usage).
- Settings are picked from preset options, not typed. The server computes the options from the machine (`docker info`: memory, CPUs) and rejects anything outside them.
  - Runtime settings (UI dropdowns, applied to the next hunt): hunt budget (`maxRuns`, `maxParallelPods`, e.g. 1/2/4 when the cap is 4), memory per runner, total runner memory. Stored in `system_settings`. Admin also has a "Clear Maven cache" button, refused while a hunt is running.
  - Startup settings (need a container restart): Kafka heap, Postgres memory, Kafka retention. Chosen once from a size preset in `deploy/` (small 8 GB / medium 16 GB / large 32 GB); the UI shows them read-only.
- Live updates via SSE; data via TanStack Query.

## 6. Kafka event design

Every state change that another service cares about is a Kafka event; keys are chosen so that events for one hunt or one test stay ordered on one partition.

| Topic                    | Producer                   | Consumers                            | Key           | Partitions (local) |
| ------------------------ | -------------------------- | ------------------------------------ | ------------- | ------------------ |
| `hunt.requested`         | project-service, gateway   | hunt-orchestrator                    | projectId     | 3                  |
| `hunt.status`            | hunt-orchestrator (outbox) | gateway (SSE), project-service       | huntId        | 3                  |
| `run.scheduled`          | hunt-orchestrator (outbox) | (audit, UI)                          | huntId        | 3                  |
| `test.results`           | test-runner                | result-aggregator                    | huntId:testId | 6                  |
| `run.completed`          | test-runner                | hunt-orchestrator, result-aggregator | huntId        | 3                  |
| `analysis.completed`     | result-aggregator          | hunt-orchestrator                    | huntId        | 3                  |
| `finding.detected`       | result-aggregator          | agent-service                        | findingId     | 6                  |
| `experiment.requested`   | agent-service              | hunt-orchestrator                    | findingId     | 3                  |
| `experiment.completed`   | hunt-orchestrator          | agent-service                        | findingId     | 3                  |
| `diagnosis.completed`    | agent-service              | result-aggregator, gateway           | findingId     | 3                  |
| `fix.proposed`           | agent-service              | result-aggregator                    | findingId     | 3                  |
| `verification.requested` | agent-service              | hunt-orchestrator                    | findingId     | 3                  |
| `fix.verified`           | hunt-orchestrator          | result-aggregator, project-service   | findingId     | 3                  |
| `ai.control`             | hunt-orchestrator          | agent-service, gateway               | "ai"          | 1                  |
| `*.dlq`                  | any consumer               | manual replay tool                   | original key  | 1                  |

### Envelope (all events)

```json
{
  "eventId": "uuid",
  "eventType": "finding.detected",
  "schemaVersion": 1,
  "occurredAt": "2026-11-02T10:15:00Z",
  "huntId": "uuid",
  "traceparent": "00-...",
  "payload": {}
}
```

### Example payloads

```json
// test.results
{ "runId": "uuid", "testId": "com.acme.OrderServiceTest#createsOrder",
  "strategy": "SHUFFLE_ORDER", "seed": 4211, "status": "FAILED",
  "durationMs": 182, "failureMessage": "expected 1 but was 2",
  "stackHash": "9f1c...", "orderIndex": 37 }

// finding.detected
{ "findingId": "uuid", "testId": "...#createsOrder", "failureRate": 0.07,
  "runs": 120, "failures": 8, "triggeringStrategies": ["SHUFFLE_ORDER"],
  "failureClusters": [{"stackHash": "9f1c...", "count": 8}], "score": 0.93 }

// experiment.requested
{ "findingId": "uuid", "experimentId": "uuid", "hypothesis": "Fails when run after CartServiceTest",
  "plan": { "tests": ["...CartServiceTest", "...#createsOrder"], "order": "FIXED",
            "repeat": 30, "strategy": "ISOLATED" } }
```

### Rules

- **Schemas:** JSON with a `schemaVersion` field in v1; move to Avro + Schema Registry only if time allows.
- **Idempotency:** consumers dedupe on `eventId` (processed-events table) or natural keys (`runId, testId`).
- **Delivery:** at-least-once everywhere; producers use `acks=all`, idempotent producer on.
- **Outbox:** hunt-orchestrator and result-aggregator write events to an `outbox` table in the same DB transaction; a relay publishes them (polling first, Debezium optional).
- **Retries:** 3 retries with backoff inside the consumer, then publish to `<topic>.dlq` with the error.
- **Retention:** `test.results` 3 days, others 14 days; Postgres is the system of record.
- **Tracing:** `traceparent` propagated through headers so one hunt is one trace across Java, Kafka and Python.

## 7. Data model

One Postgres instance with one schema per service; no service reads another service's schema. Migrations with Flyway (Java) and Alembic (Python).

```sql
-- schema: project
CREATE TABLE projects (
  id UUID PRIMARY KEY, name TEXT NOT NULL, repo_url TEXT NOT NULL,
  default_branch TEXT NOT NULL DEFAULT 'main', build_tool TEXT NOT NULL,  -- MAVEN | GRADLE
  test_command TEXT, auto_hunt_on_pr BOOLEAN DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- schema: hunt
CREATE TABLE hunts (
  id UUID PRIMARY KEY, project_id UUID NOT NULL, commit_sha TEXT NOT NULL,
  status TEXT NOT NULL, budget JSONB NOT NULL,       -- maxRuns, maxParallelPods, maxTokens
  runner_image TEXT, created_at TIMESTAMPTZ DEFAULT now(), updated_at TIMESTAMPTZ,
  version INT NOT NULL DEFAULT 0                     -- optimistic locking
);
CREATE TABLE runs (
  id UUID PRIMARY KEY, hunt_id UUID REFERENCES hunts(id), kind TEXT NOT NULL,  -- HUNT | EXPERIMENT | VERIFICATION
  strategy TEXT NOT NULL, seed BIGINT, test_filter TEXT, repeat INT DEFAULT 1,
  k8s_job_name TEXT, status TEXT NOT NULL,           -- SCHEDULED | RUNNING | SUCCEEDED | FAILED | TIMED_OUT
  started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ, experiment_id UUID
);
CREATE TABLE experiments (
  id UUID PRIMARY KEY, finding_id UUID NOT NULL, hypothesis TEXT, plan JSONB,
  status TEXT NOT NULL, result JSONB, requested_by TEXT DEFAULT 'agent'
);
CREATE TABLE system_settings (key TEXT PRIMARY KEY, value JSONB NOT NULL, updated_at TIMESTAMPTZ);
-- e.g. ('ai.enabled', 'true'), ('ai.replicas', '2')

-- schema: results
CREATE TABLE test_results (
  run_id UUID, test_id TEXT, hunt_id UUID, strategy TEXT, status TEXT,
  duration_ms INT, failure_message TEXT, stack_hash TEXT, order_index INT,
  PRIMARY KEY (run_id, test_id)                      -- idempotent inserts
);
CREATE TABLE findings (
  id UUID PRIMARY KEY, hunt_id UUID, test_id TEXT, score NUMERIC, failure_rate NUMERIC,
  evidence JSONB, status TEXT,                       -- DETECTED | DIAGNOSING | DIAGNOSED | FIX_PROPOSED | VERIFIED | FIX_FAILED
  root_cause TEXT, diagnosis JSONB, fix_diff TEXT, verification JSONB
);

-- schema: agents
CREATE TABLE agent_sessions (
  id UUID PRIMARY KEY, finding_id UUID, status TEXT, checkpoint JSONB,
  steps JSONB,                                       -- reasoning timeline shown in UI
  tokens_in INT DEFAULT 0, tokens_out INT DEFAULT 0, cost_usd NUMERIC DEFAULT 0
);
CREATE TABLE llm_calls (
  id UUID PRIMARY KEY, session_id UUID, agent TEXT, model TEXT,
  tokens_in INT, tokens_out INT, latency_ms INT, created_at TIMESTAMPTZ DEFAULT now()
);

-- every schema that publishes events
CREATE TABLE outbox (
  id UUID PRIMARY KEY, topic TEXT, key TEXT, payload JSONB,
  created_at TIMESTAMPTZ DEFAULT now(), published_at TIMESTAMPTZ
);
CREATE TABLE processed_events (event_id UUID PRIMARY KEY, processed_at TIMESTAMPTZ DEFAULT now());
```

Raw test logs and stack traces larger than a few KB go to object storage (MinIO locally, Azure Blob in cloud), referenced by key from `test_results`.

## 8. Multi-agent design

Five agents in one LangGraph graph per finding: the investigator forms hypotheses and proves them with real experiments on Kubernetes before anyone writes a fix. Agents never execute code themselves; they ask the platform to.

| Agent        | Job                                                                                                                        | Tools                                                                                                             | Model tier  |
| ------------ | -------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- | ----------- |
| Triage       | Read the finding evidence, pick likely categories, set an investigation plan                                               | `get_finding`, `get_failure_clusters`                                                                             | Small/cheap |
| Investigator | Form hypotheses, read code and logs, request experiments, decide when the cause is proven                                  | `read_file`, `search_code`, `get_test_logs`, `diff_pass_fail_logs`, `request_experiment`, `get_experiment_result` | Large       |
| Classifier   | Map the proven cause to a root-cause category with a confidence and evidence list                                          | none (structured output)                                                                                          | Small       |
| Fix writer   | Produce a minimal unified diff that removes the cause, plus rationale                                                      | `read_file`, `search_code`                                                                                        | Large       |
| Critic       | Review the diff: does it hide the failure (added retries, `@Disabled`, longer sleeps)? Is it minimal? Approve or send back | `read_file`                                                                                                       | Large       |

### Graph flow

1. **Triage** → plan with 1–3 hypotheses.
2. **Investigator loop** (max 5 iterations, max 4 experiments per finding): pick a hypothesis → request an experiment → wait for `experiment.completed` → update beliefs. Exits when a hypothesis is confirmed by an experiment (for example: fails 12/30 after test X, 0/30 alone) or the budget runs out (`UNKNOWN`).
3. **Classifier** → category + confidence.
4. **Fix writer** → diff.
5. **Critic** → approve, or return to fix writer (max 2 rounds).
6. Publish `fix.proposed` and `verification.requested`. Verification reruns the patched test with the triggering strategy, default 100 times; zero failures required.
7. On verification failure: one more fix round with the failure evidence, then mark `FIX_FAILED`.

Waiting on experiments is asynchronous: the graph checkpoints to Postgres, the pod frees the worker slot, and an `experiment.completed` event resumes the session. This is why agent pods can be killed at any time without losing work.

### Guardrails

- **Grounding:** a diagnosis must cite experiment ids or log lines; claims without evidence are rejected by the classifier.
- **Banned fixes:** the critic rejects `@Disabled`, retry annotations, increased timeouts or sleeps as the only change, and deleting assertions.
- **Budgets:** per-finding token cap, per-hunt cap, per-day cap; on cap → stop with partial diagnosis.
- **Model routing:** small model for triage and classification, large model for investigation, fixing and critique.
- **Structured output:** every agent returns JSON validated with Pydantic; one retry on invalid output.
- **Prompt injection:** repository content is data; tool results are wrapped and never treated as instructions.
- **No direct execution:** experiments only through `request_experiment`, which the orchestrator validates against the budget and allowed strategies.

### AI kill switch

The agent service is optional by design. Detection is statistical and never needs it.

1. Admin toggles **AI Agents** in the web Admin page → `PUT /api/admin/ai {enabled:false}`.
2. hunt-orchestrator stores `ai.enabled=false`, publishes on `ai.control`, and scales the `agent-service` Deployment to 0 through the Kubernetes API (with KEDA: pauses the ScaledObject using its paused annotation).
3. Pods terminate gracefully: in-flight sessions checkpoint, Kafka offsets are not committed for unfinished work.
4. New findings keep arriving on `finding.detected` and wait in Kafka. Hunts with findings move to `AWAITING_AI`. The UI shows "AI analysis paused — N findings queued".
5. Toggle on → deployment scaled back (or KEDA unpaused) → pods consume the backlog → KEDA adds replicas as lag grows → queued findings get diagnosed.

Demo value: flip the switch live, watch pods terminate and the feature degrade, flip back and watch the backlog drain and replicas rise.

## 9. Flakiness detection and hunt strategies

A test is flaky when it both passes and fails on the same commit; the strategy that triggers the failure is the first clue to its cause. Detection is pure statistics and must work with AI off.

### Strategies

| Strategy        | How it is applied                                                                         | Usually exposes                                  |
| --------------- | ----------------------------------------------------------------------------------------- | ------------------------------------------------ |
| `BASELINE`      | Default order, default config                                                             | Truly random flakiness (async waits, network)    |
| `SHUFFLE_ORDER` | Surefire random class order with a recorded seed; plus `MethodOrderer.Random` for JUnit 5 | Order dependency, shared state                   |
| `RANDOM_SEED`   | Different seeds for any seedable randomness the runner controls                           | Unseeded randomness                              |
| `PARALLEL`      | JUnit parallel execution enabled                                                          | Concurrency, shared static state                 |
| `CPU_THROTTLE`  | Low CPU limit on the Job (for example 250m)                                               | Timing assumptions, sleeps, races                |
| `TIMEZONE`      | `TZ` set to UTC, Asia/Kolkata, America/Los\_Angeles, Pacific/Kiritimati                   | Time and date bugs                               |
| `ISOLATED`      | One test alone, repeated                                                                  | Separates order effects from intrinsic flakiness |

### Default hunt plan

- 3 baseline runs first. If the baseline fails consistently, the hunt stops: that is a broken test, not a flaky one.
- Then perturbed runs split across strategies, default total 40 runs locally (configurable), max parallel pods 4–8 locally.
- Each run records its seed and test order so any failure can be replayed exactly.

### Scoring

For each test with n runs and f failures (excluding skips):

- Flaky candidate if 0 < f < n.
- Failure rate p = f / n, with a 95% Wilson interval to show uncertainty.
- Strategy lift: failure rate under a strategy divided by baseline failure rate (a test that fails only under `SHUFFLE_ORDER` has high order lift).
- Failure clusters: group failures by `stackHash` + normalized message; one cluster suggests one cause.
- Score combines: has both outcomes (required), number of failures (more evidence, higher score), and cluster consistency. Start simple, tune against the benchmark.

### Known limits

- Low-rate flakiness (under 1%) needs many runs; local budgets will miss some. Report it as "not detected within budget", not "not flaky".
- Tests that depend on external services are flagged `NETWORK` and not fixed automatically.
- JUnit 4 targets are shuffled by class only, so order dependencies between methods of the same JUnit 4 class are missed (ADR 0004).
- Implementation-dependent tests (relying on HashMap or reflection order) are not exposed by reordering. They need a NonDex strategy, which is not planned for v1. Phase 0 found that some IDoFT "OD" tests are really this kind.

## 10. Kubernetes and deployment

Three environments share one set of Helm charts: Docker Compose for fast local development, k3d on the Mac for the real Kubernetes setup, and a short-lived Azure cluster for the public demo.

| Environment | Runs on                                | Used for                              |
| ----------- | -------------------------------------- | ------------------------------------- |
| `dev`       | Docker Compose                         | Phase 1 development, fastest feedback |
| `local-k8s` | k3d (or OrbStack K8s) + local registry | Jobs, KEDA, Helm, kill switch         |
| `cloud`     | AKS (or Azure VM with k3s)             | Demo, benchmark runs, recording       |

### Namespaces

- `flakehunter` — platform services.
- `flakehunter-runners` — runner Jobs only, with a `ResourceQuota` (caps total CPU/memory and pod count) and a `NetworkPolicy` that allows only Kafka and the Maven proxy.
- `infra` — Kafka, Postgres, Redis, MinIO, observability stack.

### Workloads

| Workload          | Kind                                   | Replicas (local) | Notes                                                                                      |
| ----------------- | -------------------------------------- | ---------------- | ------------------------------------------------------------------------------------------ |
| api-gateway       | Deployment                             | 1                | Ingress (Traefik in k3d)                                                                   |
| project-service   | Deployment                             | 1                |                                                                                            |
| hunt-orchestrator | Deployment                             | 1 (2 in cloud)   | ServiceAccount with RBAC to create Jobs in `flakehunter-runners` and scale `agent-service` |
| result-aggregator | Deployment                             | 1, KEDA up to 3  | Scaled on `test.results` lag                                                               |
| agent-service     | Deployment                             | KEDA 0–3         | Scaled on `finding.detected` lag; paused by kill switch                                    |
| web               | Deployment                             | 1                | Nginx static                                                                               |
| test-runner       | Job                                    | per run          | `backoffLimit: 0`, `activeDeadlineSeconds`, `ttlSecondsAfterFinished: 300`                 |
| Kafka             | StatefulSet (Strimzi or Bitnami chart) | 1 broker         | KRaft                                                                                      |
| Postgres          | StatefulSet                            | 1                | PVC                                                                                        |

Every Deployment has readiness/liveness probes (Spring Actuator, FastAPI `/health`), resource requests and limits, and graceful shutdown.

### Helm

- `charts/<service>` per service; `charts/flakehunter` umbrella with values per environment (`values-dev.yaml`, `values-local.yaml`, `values-cloud.yaml`).
- Secrets: LLM API keys, JWT secret, GitHub App key as Kubernetes Secrets locally; Azure Key Vault (CSI driver) in cloud if time allows.

### CI/CD (GitHub Actions)

1. On PR: build and unit-test each changed service; Testcontainers integration tests; lint; build images (no push).
2. On merge to `main`: build multi-arch images (`linux/arm64`, `linux/amd64`), tag with git SHA, push to GHCR.
3. Deploy: `helm upgrade --install` to the cloud cluster when it exists (manual approval), or Argo CD watching an env repo (optional).

### Cloud cost rules

- Create the cluster only for demo or benchmark sessions; destroy it after (scripted with Terraform or `az` CLI).
- Small node pool (2 nodes) plus a spot node pool for runner Jobs if available.
- Public demo mode: only 3–4 preselected repos, hunt budget capped, AI budget capped per day.

## 11. Observability, reliability and security

One hunt should be traceable end to end in Grafana, any pod should be killable without losing work, and untrusted repository code must never touch the platform.

### Observability

- **Tracing:** OpenTelemetry Java agent on Spring services, OpenTelemetry SDK in Python; `traceparent` in Kafka headers; one trace per hunt in Tempo or Jaeger.
- **Metrics (Prometheus):** hunts by status, runs in flight, runner pod count, Job duration, `test.results` throughput, consumer lag per group, findings per hunt, agent session duration, LLM tokens and cost, verification pass rate.
- **Dashboards (Grafana):** Platform (lag, pods, errors), Hunt (progress, run outcomes), AI (tokens, cost, sessions, kill-switch state).
- **Logs:** structured JSON with `huntId`, `findingId`, `traceId` on every line.

### Reliability

| Failure                             | Behaviour                                                                                                                  |
| ----------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| Runner pod crashes or times out     | Run marked `FAILED`/`TIMED_OUT`; excluded from scoring; retried once if budget allows                                      |
| result-aggregator restarts          | Consumers resume from committed offsets; inserts are idempotent; Analyzer picks up unanalyzed hunts                        |
| hunt-orchestrator restarts mid-hunt | State in Postgres; reconciler loop compares DB runs with live Jobs and repairs                                             |
| Kafka unavailable                   | Outbox rows accumulate; relay publishes when Kafka is back                                                                 |
| agent-service killed (or AI off)    | Sessions checkpointed; unfinished findings re-consumed; hunt waits in `AWAITING_AI`                                        |
| LLM API errors / rate limits        | Retry with backoff and jitter, circuit breaker (Resilience4j-style in Python via tenacity), fall back to a second provider |
| Poison message                      | 3 retries, then `.dlq` topic with error details; replay tool                                                               |

### Security

- Runner pods: non-root user, read-only root filesystem except a work dir, no service-account token, dropped capabilities, `NetworkPolicy` allowing only Kafka and the Maven proxy, CPU/memory limits, deadline.
- The repository is cloned and built in a separate prepare Job, not inside platform pods.
- Secrets only from Kubernetes Secrets; never in images or logs.
- GitHub webhooks verified with HMAC signature; GitHub App tokens are short-lived and scoped to the installed repos.
- Gateway: JWT auth, rate limits, CORS restricted to the web origin.
- LLM prompts treat repository content as untrusted data (section 8).

## 12. Repository structure and conventions

One monorepo, so one PR can change an event schema and every service that uses it.

```
flakehunter/
├── services/
│   ├── api-gateway/            # Spring Cloud Gateway
│   ├── project-service/        # Spring Boot
│   ├── hunt-orchestrator/      # Spring Boot + fabric8
│   ├── result-aggregator/      # Spring Boot, Kafka consumers + SQL scoring
│   └── agent-service/          # Python, FastAPI, LangGraph
├── runner/
│   ├── publisher/              # parses JUnit XML, publishes test.results + run.completed
│   └── images/                 # base runner Dockerfiles, one per JDK
├── web/                        # React + Vite + TypeScript
├── libs/
│   └── events/                 # shared event schemas (JSON Schema) + generated Java/Python types
├── deploy/
│   ├── compose/                # docker-compose for dev
│   ├── charts/                 # Helm charts + umbrella
│   ├── k3d/                    # local cluster config, registry
│   └── cloud/                  # Terraform or az scripts
├── benchmark/                  # target repo list, known flaky tests, eval scripts
├── docs/                       # this document, ADRs, diagrams
└── .github/workflows/
```

### Conventions

- **Java:** Gradle (Kotlin DSL) multi-project build, Java 21, package `dev.flakehunter.<service>`, layered by feature not by layer type, MapStruct optional, Testcontainers for Kafka/Postgres tests.
- **Python:** `uv` or Poetry, Ruff + mypy, Pydantic models generated from `libs/events`.
- **API:** REST with OpenAPI generated from code; errors as RFC 7807 problem details.
- **Events:** schema change = new `schemaVersion`; consumers accept current and previous version.
- **Commits:** Conventional Commits (`feat(orchestrator): ...`).
- **Decisions:** each significant choice recorded as a short ADR in `docs/adr/` (context, decision, alternatives, consequences). These double as interview notes.
- **Definition of done for any feature:** tests, metrics/logs added, README section updated, works in `local-k8s`.

## 13. Phased roadmap

Five phases over about 13 weeks, from 18 Oct 2026 to 18 Jan 2027, each ending in something that runs. Never start a phase before the previous exit criterion is met.

### Phase 0 — Prove the core (18–26 Oct)

- [x] Pick 2–3 public Java repos with known flaky tests (`benchmark/targets.md`)
- [x] Script that clones a repo, runs its tests N times in Docker with shuffled order and seeds, and parses JUnit XML (`benchmark/hunt.py`)
- [x] Script reports which tests both passed and failed
- [x] Pin tool versions; create the monorepo skeleton (ADR 0001)
- [x] Settle the open decisions about runner result transport and image strategy (ADRs 0002–0004)

**Exit:** a flaky test detected on a real repo by a dumb script. Met on 2026-10-19: fastjson's `DateTest4_indian` and `DateTest5_iso8601` were flagged in 10 runs.

### Phase 1 — Platform on Docker Compose (27 Oct – 16 Nov)

- [x] Postgres, Kafka (KRaft), Compose file
- [x] project-service and hunt-orchestrator with Flyway, outbox relay, hunt state machine
- [x] Runner base images + publisher sending `test.results` and `run.completed`; remove `runner/junit-extension`
- [x] result-aggregator with scoring, `finding.detected`, `analysis.completed` (SQL, not Kafka Streams: ADR 0005)
- [x] api-gateway with JWT; minimal React pages: projects, start hunt, findings list
- [x] Machine-aware preset options: start-hunt budget dropdowns, Admin Runner panel (memory, clear Maven cache), size presets in `deploy/` for Kafka/Postgres
- [x] Testcontainers integration tests for the event flow

**Exit:** start a hunt from the UI, runner containers run, a flaky test shows up as a finding.

### Phase 2 — Kubernetes (17 Nov – 7 Dec)

- [ ] k3d cluster with local registry; Helm charts for every service + umbrella
- [ ] Orchestrator creates runner Jobs with fabric8; RBAC, quotas, NetworkPolicy
- [ ] Reconciler loop for DB vs live Jobs
- [ ] KEDA for result-aggregator on lag
- [ ] Prometheus + Grafana with platform dashboard

**Exit:** a hunt fans out runner pods in the cluster and pods clean up after themselves; killing any platform pod mid-hunt does not lose the hunt.

### Phase 3 — Agents (8–28 Dec)

- [ ] agent-service skeleton: FastAPI, Kafka consumer, Postgres checkpoints, budgets
- [ ] Triage and investigator with `request_experiment` round trip through the orchestrator
- [ ] Classifier, fix writer, critic; verification runs
- [ ] AI kill switch end to end, KEDA scale-to-zero for agent-service
- [ ] Agent reasoning timeline and diff view in the UI

**Exit:** one finding goes from detected → diagnosed with real experiments → fix verified 100/100; switching AI off and on drains the queued backlog.

### Phase 4 — Production and showcase (29 Dec – 18 Jan)

- [ ] OpenTelemetry tracing across services; AI and hunt dashboards
- [ ] GitHub App: webhook-triggered hunts and PR comments
- [ ] GitHub Actions CI/CD with multi-arch images
- [ ] Cloud deploy with scripted create/destroy
- [ ] Benchmark run and results table
- [ ] README, demo video, first write-up

**Exit:** public README with benchmark numbers and a demo video recorded on the cloud cluster.

### If time runs short

Cut in this order: GitHub App, Argo CD, Avro/Schema Registry, Redis, Gradle support, cloud deploy (keep a local-k8s recording instead). Never cut: Kafka flow, runner Jobs, the agent experiment loop, the kill switch, the benchmark.

## 14. Evaluation, showcase and open decisions

The project is judged by one honest benchmark number and one live demo, not by feature count.

### Benchmark

- Build `benchmark/` from public Java projects with documented flaky tests: academic datasets such as iDFlakies list real flaky tests in open-source Java projects; GitHub issues titled "flaky test" are a second source. Verify each candidate before using it.
- Also seed a small control repo with one flaky test per root-cause category, so every category is covered.
- Metrics: detection recall within budget, false positives, correct root-cause category, fixes proposed, fixes verified, average LLM cost per finding, hunt duration.
- Report results honestly in the README, including misses.

### Showcase

- **README:** problem, architecture diagram, benchmark table, demo GIF, how to run locally in one command.
- **Demo video (3–4 min):** start hunt → pods fan out → live run grid → finding appears → agent reasoning timeline with real experiments → diff → 100/100 verification → flip AI off, pods terminate, findings queue → flip on, backlog drains.
- **Write-ups:** 3–4 LinkedIn posts or a blog series (detection statistics, agents that run experiments, KEDA scale-to-zero, outbox + saga in practice).
- **Public demo:** cloud cluster during demo windows only, preselected repos.

### Interview talking points

- Why each service is separate, and what would merge if traffic were small.
- Exactly-once vs at-least-once: outbox, idempotent consumers.
- Why KEDA on lag instead of CPU-based HPA.
- The saga for a hunt and how the reconciler repairs drift between DB and live Jobs.
- Agents that wait on real experiments: checkpointing and resuming across pods.
- Graceful degradation with the AI kill switch.
- Running untrusted code safely on Kubernetes.
- Statistics of detection: why low-rate flakiness needs more runs, Wilson intervals.
- LLM cost control: routing, budgets, critic loop limits.

### Open decisions (settle in Phase 0–1, record as ADRs)

- [x] Result transport from runner: JUnit XML + publisher in the same container (ADR 0002)
- [x] Runner image: base image per JDK + compile once per hunt (ADR 0003)
- [x] Test frameworks: JUnit 4 and 5 (ADR 0004)
- [ ] Kafka on K8s: Strimzi operator vs Bitnami chart
- [ ] JSON Schema now vs Avro + Schema Registry later
- [ ] LLM providers and default models per agent tier
- [ ] Cloud target: AKS vs Azure VM + k3s (cost vs realism)
- [ ] Gradle support in v1 or Maven only
