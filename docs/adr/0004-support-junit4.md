# ADR 0004 — Support JUnit 4 and JUnit 5 target repositories in v1

- Status: Accepted
- Date: 2026-10-19
- Amends: ADR 0001, deviation 2 (the runner no longer compiles against JUnit 5)

## Context

The tech doc and ADR 0001 scoped v1 to JUnit 5 repositories and planned a JUnit 5 extension for the runner. In Phase 0, we searched IDoFT for order-dependent flaky tests in maintained repositories and checked the build files of the six best candidates (jackson-dataformat-xml, jnr-posix, light-4j, Sentinel, hutool, jboot), plus fastjson and TestParameterInjector. **All of them use JUnit 4.** The only target we reproduced, fastjson, is JUnit 4.

A JUnit 5-only tool would miss most of the real flaky tests that are known and documented, including our whole benchmark.

## Decision

v1 supports Maven repositories whose tests use JUnit 4 or JUnit 5 (Jupiter, including JUnit 4 tests that run on the Vintage engine).

- Reading results from Surefire XML (ADR 0002) covers both frameworks with no extra code.
- The order strategy shuffles **test classes** through Surefire's random run order, which works for both.
- Shuffling **methods within a class** is JUnit 5 only (`MethodOrderer.Random`, set through `junit.jupiter.testmethod.order.default`). For JUnit 4 targets, the `SHUFFLE_ORDER` strategy only reorders classes. This limit is recorded in the tech doc (section 9, Known limits).

## Consequences

- The benchmark can use IDoFT's catalog, which is mostly JUnit 4.
- `runner/junit-extension` and the `junit5-runner` catalog entry are no longer needed. Remove them when `runner/publisher` replaces them in Phase 1.
- Order-dependent bugs between methods of the same JUnit 4 class are not found in v1.
- Gradle targets remain an open decision (tech doc section 14).
