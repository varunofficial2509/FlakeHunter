# ADR 0003 — Runner uses base images per JDK and compiles the target once per hunt

- Status: Accepted
- Date: 2026-10-19

## Context

A hunt runs the same commit's tests many times. The tech doc left open whether to build a runner image per project and commit (JDK, build tool and pre-fetched dependencies baked in), or to use one base image plus a dependency cache.

The Phase 0 prototype showed:

- The JDK must match the target. fastjson only builds on JDK 8 (its pom targets Java 1.5). jackson-dataformat-xml 2.13 fails a test on JDK 21 because of module access, but passes on JDK 11. One JDK cannot serve every target.
- Compiling once and then running only the tests made each run take seconds. Downloading dependencies on the first build took far longer than the runs themselves. A shared Maven cache (a Docker volume) removed that cost after the first time.
- Old target poms pin old Surefire versions. Invoking `maven-surefire-plugin:3.5.2:test` explicitly gave a working, seedable random order on every target.

## Decision

- **Base images, one per JDK major version:** 8, 11, 17 and 21. Each holds Temurin, Maven 3.9.16 and the publisher from ADR 0002. They are built from `runner/images/` and pinned in `deploy/images.env`. Nothing is built per commit.
- **Choose the JDK per project:** read it from the pom (`maven.compiler.release`, `source`/`target`, or common properties such as `jdk.version`). A field on the project lets the user override it.
- **Prepare once per hunt (`PREPARING`):** clone at the SHA, resolve dependencies and run `test-compile` once into a workspace and dependency cache. Every run reuses them and only runs tests.
- **Runs invoke Surefire 3.x explicitly** with `-Dsurefire.runOrder=random -Dsurefire.runOrder.random.seed=<seed>`, so a failing seed can be replayed exactly.
- **The Kubernetes storage details** (a PVC per hunt, or a copy per pod, since Surefire writes into `target/`) are settled in Phase 2. Phase 1 uses Docker volumes, as the prototype did.

## Alternatives considered

- **Image per project and commit.** Everything is baked in, and pods start fast once it exists. But each hunt waits for an image build and push, the local registry fills up on a 16 GB laptop, and the cached layers stop helping as soon as the commit changes.

## Consequences

- Four small base images to maintain, upgraded like any other pinned image (ADR 0001).
- The first hunt for a project pays the dependency download, and later hunts reuse the cache.
- Runs are fast and need no network except Kafka and the Maven proxy, which matches the runner's network policy.
- Getting the JDK wrong fails at `test-compile`. The orchestrator surfaces that as a `PREPARING` failure that tells the user to set the JDK override.
