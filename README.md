# FlakeHunter

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

The whole platform (4 services, web app, Postgres, Kafka) on a one-node k3d cluster inside Docker. Needs k3d, Helm and kubectl. Stop the Compose stack first: both together don't fit in Docker's memory.

```
FLAKEHUNTER_ADMIN_PASSWORD=pick-one deploy/k3d/up.sh   # the password is only needed the first time
```

Then open http://localhost:8090 and log in as `admin`. Rerun `deploy/k3d/up.sh` after any code change: it rebuilds the images and rolls them out.

| Task               | Command                                                                          |
| ------------------ | -------------------------------------------------------------------------------- |
| See the pods       | `kubectl --context k3d-flakehunter -n flakehunter get pods`                      |
| Logs of a service  | `kubectl --context k3d-flakehunter -n flakehunter logs deploy/hunt-orchestrator` |
| Pause (keeps data) | `k3d cluster stop flakehunter`, later `k3d cluster start flakehunter`            |
| Delete everything  | `k3d cluster delete flakehunter` (also deletes the data and the registry)        |
