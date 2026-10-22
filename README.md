# FlakeHunter

[![CI](https://github.com/varunofficial2509/FlakeHunter/actions/workflows/ci.yml/badge.svg)](https://github.com/varunofficial2509/FlakeHunter/actions/workflows/ci.yml)

## Local infrastructure

Postgres and Kafka run in Docker Compose. Only Docker is needed. Run from the repo root:

```
docker compose --env-file deploy/images.env --env-file deploy/presets/medium.env -f deploy/compose/compose.yaml up -d --wait
```

Pick the size preset that matches your machine; it sets Kafka's heap, Postgres's memory, both containers' memory limits and how long Kafka keeps events:

| Preset   | Machine | Kafka + Postgres limit | Keeps test results / other events | Orchestrator setting              |
| -------- | ------- | ---------------------- | --------------------------------- | --------------------------------- |
| `small`  | 8 GB    | 768 MB                 | 1 / 7 days                        | `FLAKEHUNTER_INFRA_MEMORY=768MB`  |
| `medium` | 16 GB   | 1 GB                   | 3 / 14 days                       | none (the default)                |
| `large`  | 32 GB   | 1.5 GB                 | 7 / 30 days                       | `FLAKEHUNTER_INFRA_MEMORY=1536MB` |

The orchestrator keeps that much of Docker's memory free when it offers runner sizes, so export the setting before starting it. To switch presets, rerun the same command with the other file: Compose recreates Kafka and Postgres (data is kept), and kafka-init applies the new retention to existing topics.

| Service  | From your Mac    | From other containers |
| -------- | ---------------- | --------------------- |
| Postgres | `localhost:5432` | `postgres:5432`       |
| Kafka    | `localhost:9092` | `kafka:29092`         |

Postgres database, user and password are all `flakehunter` (local only). The Kafka topics from the tech doc (section 6) are created on startup.

Stop with `down`. `down -v` also **deletes all data**.

## Local Kubernetes (Phase 2)

The whole platform (5 services, web app, Postgres, Kafka) on a one-node k3d cluster inside Docker. Needs k3d, Helm and kubectl. Stop the Compose stack first: both together don't fit in Docker's memory.

```
FLAKEHUNTER_ADMIN_PASSWORD=pick-one deploy/k3d/up.sh   # the password is only needed the first time
```

Then open http://localhost:8090 and log in as `admin`. Rerun `deploy/k3d/up.sh` after any code change: it rebuilds the images and rolls them out.

Hunts run as Kubernetes Jobs in the `flakehunter-runners` namespace. Its quota (3 GB, `runners.memoryMb` in the chart) fits about 2 runners at once; a Job that doesn't fit waits for room. Runners can reach Kafka and the internet, nothing else. The first `up.sh` builds runner images for JDK 8, 11, 17 and 21; `RUNNER_JDKS="17" deploy/k3d/up.sh` builds only the ones you need.

Metrics: Grafana at http://localhost:8090/grafana opens on the **FlakeHunter platform** dashboard (consumer lag, replicas, hunts by status, runs in flight, 5xx, JVM heap); log in as `admin` to edit. Prometheus scrapes every service's `/actuator/prometheus` and keeps 1 day. Two more dashboards read Postgres through a read-only role: **FlakeHunter hunts** (run outcomes per strategy, recent hunts, findings) and **FlakeHunter AI** (LLM calls, tokens per agent and model, latency, session outcomes). Traces from every service, the Python agents included, go to Tempo (kept 24 h): open Explore → Tempo in Grafana and search by service, or with TraceQL such as `{ name = "finding.detected process" }`. KEDA scales `result-aggregator` from 1 to 2 replicas when its lag on `test.results` passes 5,000 per replica (`keda.maxReplicas` and `keda.lagThreshold` in the chart; up to 6, the topic's partition count). To watch it happen, run `deploy/k3d/flood-test-results.sh`.

Agents (Phase 3): `agent-service` (Python) reads `finding.detected` and runs one LangGraph session per finding, checkpointed in Postgres (`agents` schema). Give it a Gemini key with `GEMINI_API_KEY=... deploy/k3d/up.sh`; without one it uses a fake LLM, so the pipeline runs but nothing is really diagnosed. Token caps per finding, hunt and day are `FLAKEHUNTER_TOKENS_PER_*` (ADR 0006). Tests: `cd services/agent-service && uv run pytest`.

| Task               | Command                                                                          |
| ------------------ | -------------------------------------------------------------------------------- |
| See the pods       | `kubectl --context k3d-flakehunter -n flakehunter get pods`                      |
| Logs of a service  | `kubectl --context k3d-flakehunter -n flakehunter logs deploy/hunt-orchestrator` |
| Runner Jobs        | `kubectl --context k3d-flakehunter -n flakehunter-runners get jobs,pods`         |
| Logs of a run      | `kubectl --context k3d-flakehunter -n flakehunter-runners logs job/run-<runId>`  |
| Autoscaling state  | `kubectl --context k3d-flakehunter -n flakehunter get scaledobject,hpa`          |
| Pause (keeps data) | `k3d cluster stop flakehunter`, later `k3d cluster start flakehunter`            |
| Delete everything  | `k3d cluster delete flakehunter` (also deletes the data and the registry)        |

## CI and images

Every pull request and every push to `develop` or `main` runs `.github/workflows/ci.yml`: the Gradle build with all Java tests (Testcontainers on the runner's Docker), agent-service lint and tests, the web typecheck, build and tests, `helm lint`, and the pre-commit hooks (formatting, secret scan).

Each push to `main` runs `.github/workflows/images.yml`, which pushes every image for `linux/amd64` and `linux/arm64` to `ghcr.io/varunofficial2509/flakehunter/<name>` with tags `sha-<commit>` and `latest` (runner images: `jdkN-sha-<commit>` and `jdkN`). New GHCR packages start private; make them public under the repo's Packages if a cluster should pull without a login.
