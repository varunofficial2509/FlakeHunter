# Benchmark targets

Known flaky tests from IDoFT (https://github.com/winglam/idoft-637/blob/main/pr-data.csv).
Categories: OD = order-dependent (found by shuffling), ID = implementation-dependent
(found by NonDex, which shuffles HashMap/HashSet and reflection order), NOD = non-deterministic.

## Reproduced

| Repo                                | SHA                                      | Known flaky test                                                 | Category | Fix                                           |
| ----------------------------------- | ---------------------------------------- | ---------------------------------------------------------------- | -------- | --------------------------------------------- |
| https://github.com/alibaba/fastjson | e05e9c5e4be580691cc55a59f3256595393203a1 | com.alibaba.json.bvt.serializer.date.DateTest4_indian.test_date  | OD       | https://github.com/alibaba/fastjson/pull/2148 |
| https://github.com/alibaba/fastjson | e05e9c5e4be580691cc55a59f3256595393203a1 | com.alibaba.json.bvt.serializer.date.DateTest5_iso8601.test_date | OD       | https://github.com/alibaba/fastjson/pull/2148 |

Cause: `serializer.date.DateTest.setUp` sets the global `JSON.defaultTimeZone`/`defaultLocale` and never resets them; the two victims pass only when it ran first.

```
uv run --no-project --python 3.12.15 benchmark/hunt.py https://github.com/alibaba/fastjson \
  e05e9c5e4be580691cc55a59f3256595393203a1 --runs 10 \
  --image maven:3.9.16-eclipse-temurin-8 --define jdk.version=1.8 --tests '**/serializer/date/*Test*'
```

Result (2026-10-19): both flagged in 10 runs (failed 4x and 3x), ~16 s with a warm cache.

## Not reproduced by shuffling

| Repo                                                | SHA                                                      | Known flaky test                                                                                      | IDoFT category | Why not                                                                                                                                                                                      |
| --------------------------------------------------- | -------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- | -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| https://github.com/FasterXML/jackson-dataformat-xml | 2049d6a0d9b7c74d8530737de4d91a74f7a8c62c (tag 2.13.0)    | com.fasterxml.jackson.dataformat.xml.ser.TestSerializationAttr.testIssue6 (+7 more, fixed by PR #499) | OD             | Depends on reflection field order, which HotSpot keeps stable: effectively ID. 0/316 flaky in 10 runs on JDK 11. IDoFT's SHA (2d08f8d0) no longer builds: its 3.0.0-SNAPSHOT parent is gone. |
| https://github.com/google/TestParameterInjector     | 5d67f8cb66481638bcd117227bd0103c32c84491 (module junit4) | com.google.testing.junit.testparameterinjector.TestParameterTest.test[WithValuesProvider]             | ID             | Needs a NonDex strategy. Not run.                                                                                                                                                            |

## Lessons for the runner

- Pin a released commit: SNAPSHOT parents disappear and the build rots.
- The JDK must match the target: fastjson needs JDK 8 (`-Djdk.version=1.8`, its pom targets 1.5); jackson 2.13 fails one test on JDK 21 (module access) but passes on JDK 11.
- Run Surefire 3.x explicitly (`maven-surefire-plugin:3.5.2:test`) for a fixed `runOrder.random.seed`; old poms pin older versions.
- `-Dtest` needs path patterns (`**/pkg/*Test*`); a dotted `pkg.*` silently matches nothing.
- IDoFT's category is a hint, not truth. Many "flaky" tests need NonDex, not reordering.
- All candidates are JUnit 4: the runner must support it, not only JUnit 5.
