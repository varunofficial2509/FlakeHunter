# FlakeHunter

## Local infrastructure

Postgres and Kafka run in Docker Compose. Only Docker is needed. Run from the repo root:

```
docker compose --env-file deploy/images.env -f deploy/compose/compose.yaml up -d --wait
```

| Service  | From your Mac    | From other containers |
| -------- | ---------------- | --------------------- |
| Postgres | `localhost:5432` | `postgres:5432`       |
| Kafka    | `localhost:9092` | `kafka:29092`         |

Postgres database, user and password are all `flakehunter` (local only). The Kafka topics from the tech doc (section 6) are created on startup.

Stop with `down`. `down -v` also **deletes all data**.
