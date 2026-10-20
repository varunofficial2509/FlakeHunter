# ADR 0001 — Pin exact tool and framework versions

- Status: Accepted
- Date: 2026-10-18

## Context

The technical documentation (section 2) gives target versions such as "Spring Boot 3.x" and "React 18", and says to pin exact versions in Phase 0. Floating versions make builds non-reproducible, and in a polyglot monorepo a silent upgrade in one service is hard to trace.

## Decision

Every tool, runtime, library and image is pinned to an exact version, recorded in one machine-readable file per ecosystem:

| Ecosystem                 | Source of truth                                                       |
| ------------------------- | --------------------------------------------------------------------- |
| Gradle                    | `gradle/wrapper/gradle-wrapper.properties`                            |
| JVM libraries and plugins | `gradle/libs.versions.toml` (plus the Spring Boot BOM)                |
| JDK                       | `.java-version`                                                       |
| Python and its libraries  | `services/agent-service/.python-version`, `pyproject.toml`, `uv.lock` |
| Node and web libraries    | `web/.nvmrc`, `web/package.json`, `web/pnpm-lock.yaml`                |
| Container images          | `deploy/images.env`                                                   |
| Pre-commit hooks          | `.pre-commit-config.yaml`                                             |

These are the rules for choosing a version:

- **Libraries, frameworks and CLI tools:** the newest stable release published at least 14 days before the pin date (on or before 2026-10-04), within the major line the tech doc chose. Brand-new releases get two weeks for regressions to show up.
- **Runtimes and base images** (JDK, Python, Node, Postgres and so on): the newest patch release of the chosen line, because patches carry security fixes.
- **Spring Boot BOM:** anything the BOM manages is not pinned separately. That covers Kafka clients and Streams, Flyway, the Postgres driver, Testcontainers, JUnit, Jackson and Micrometer. Overriding the BOM causes version skew.
- **Shared tools** must be the same version everywhere: Ruff and Prettier match the pre-commit hook revs.

## Pinned versions

### Toolchain

| Tool                             | Version                          |
| -------------------------------- | -------------------------------- |
| JDK (Temurin)                    | 21.0.12.1+1                      |
| Gradle (wrapper)                 | 9.8.0                            |
| Foojay toolchain resolver        | 1.0.0                            |
| Maven (inside runner image only) | 3.9.16                           |
| Python                           | 3.12.15                          |
| uv                               | 0.12.23                          |
| Node.js (LTS)                    | 20.20.2                          |
| pnpm                             | 10.34.7                          |
| Docker Engine                    | 29.x (host-provided, not pinned) |
| kubectl                          | 1.35.9                           |
| k3d                              | 5.9.0                            |
| k3s (in k3d)                     | v1.35.9-k3s1                     |
| Helm                             | 4.3.0                            |
| KEDA                             | 2.21.0                           |
| Terraform                        | 1.16.5                           |
| gitleaks                         | 8.30.1                           |

### JVM

| Library                          | Version  | Notes                              |
| -------------------------------- | -------- | ---------------------------------- |
| Spring Boot                      | 4.0.8    | See deviation 1 below              |
| Spring Cloud                     | 2025.1.3 | Brings Spring Cloud Gateway 5.0.3  |
| Kafka clients and Streams        | 4.1.2    | Managed by the Boot BOM            |
| Flyway                           | 11.14.1  | Managed by the Boot BOM            |
| PostgreSQL JDBC                  | 42.7.13  | Managed by the Boot BOM            |
| Testcontainers                   | 2.0.5    | Managed by the Boot BOM            |
| JUnit (services)                 | 6.0.3    | Managed by the Boot BOM            |
| JUnit Jupiter (runner extension) | 5.14.4   | See deviation 2 below              |
| fabric8 Kubernetes client        | 8.0.0    | Uses Jackson 3, the same as Boot 4 |
| springdoc-openapi                | 3.1.1    |                                    |
| MapStruct                        | 1.6.3    | Optional                           |
| OpenTelemetry Java agent         | 2.32.0   |                                    |

### Python (agent-service)

| Library                       | Version         |
| ----------------------------- | --------------- |
| FastAPI                       | 0.142.2         |
| Uvicorn                       | 0.54.0          |
| LangGraph                     | 1.2.12          |
| langgraph-checkpoint-postgres | 3.1.2           |
| langchain-core                | 1.6.6           |
| langchain-openai              | 1.6.7           |
| langchain-google-genai        | 4.4.0           |
| Pydantic                      | 2.13.5          |
| pydantic-settings             | 2.15.0          |
| aiokafka                      | 0.14.0          |
| SQLAlchemy                    | 2.1.3           |
| Alembic                       | 1.20.0          |
| psycopg                       | 3.3.6           |
| tenacity                      | 9.1.4           |
| httpx                         | 0.28.1          |
| OpenTelemetry SDK / distro    | 1.45.0 / 0.66b0 |
| pytest / pytest-asyncio       | 9.1.1 / 1.4.0   |
| testcontainers (Python)       | 4.15.0          |
| Ruff                          | 0.17.1          |
| mypy                          | 2.4.0           |
| datamodel-code-generator      | 0.83.0          |

### Web

| Library                    | Version          | Notes                                      |
| -------------------------- | ---------------- | ------------------------------------------ |
| React / React DOM          | 18.3.1           |                                            |
| React Router               | 7.18.4           | v8 requires React 19                       |
| TanStack Query             | 5.104.1          |                                            |
| TypeScript                 | 6.0.3            | typescript-eslint does not support 7.x yet |
| Vite                       | 8.3.2            |                                            |
| @vitejs/plugin-react       | 6.1.1            |                                            |
| Vitest                     | 5.0.3            |                                            |
| Playwright                 | 1.63.0           |                                            |
| ESLint / typescript-eslint | 10.12.0 / 8.71.0 |                                            |
| Prettier                   | 3.9.10           | Matches the pre-commit rev                 |

### Images

The tags are listed in `deploy/images.env`. They are Kafka 4.1.2 (KRaft), PostgreSQL 16.15, Redis 8.10.2, SeaweedFS 4.48, Prometheus 3.15.0, Grafana 13.2.4, Tempo 3.0.4, Loki 3.6.18 and Nginx 1.31.7.

## Deviations from the tech doc

1. **Spring Boot 4.0 instead of 3.x.** Open-source support for Boot 3.5 ended with 3.5.16 in June 2026, before this project started. Boot 4.1 is newer, but no Spring Cloud release train supports it yet (2026.0 is only at milestone 1), and the gateway needs Spring Cloud. Boot 4.0.8 with Spring Cloud 2025.1.3 is therefore the newest supported pair.
2. **JUnit 5 for the runner extension, JUnit 6 for the services.** The extension runs inside target repositories, and the v1 scope is JUnit 5 repos, so it must not pull in JUnit 6.
3. **SeaweedFS instead of MinIO.** MinIO no longer publishes community container images. SeaweedFS is S3-compatible, so the code only depends on the S3 API, and Azure Blob is still used in the cloud.
4. **Kafka on Kubernetes.** Bitnami's free charts and images are deprecated, which leaves Strimzi as the realistic option. That decision stays open (tech doc section 14). If it goes ahead, the candidate version is Strimzi 1.2.0.

## Consequences

- Upgrades are deliberate PRs that change one source-of-truth file and its lockfile.
- Local tools must match: install JDK 21.0.12.1, Node 20.20.2, uv 0.12.23, Helm 4.3.0, k3d 5.9.0 and kubectl 1.35.9.
- Revisit this ADR at the start of every phase, and move to Boot 4.1 once a Spring Cloud release train supports it.
