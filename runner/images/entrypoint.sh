#!/usr/bin/env bash
# One hunt run: apply the strategy, run the already-compiled tests once, then publish the results.
# Inputs (tech doc section 5): HUNT_ID, RUN_ID, STRATEGY, SEED, TEST_FILTER, TZ, KAFKA_BOOTSTRAP_SERVERS,
# RUN_TIMEOUT_SECONDS. The prepared workspace is mounted at /workspace.
set -uo pipefail # no -e: a failing test run is normal and must still be published

# Surefire 3.x is invoked explicitly: old target poms pin versions without a fixed random seed (ADR 0003).
args=(-B -q org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
  # A pom that reruns failures would turn flaky failures into passes and hide exactly what we hunt.
  -Dsurefire.rerunFailingTestsCount=0)
[[ -n "${TEST_FILTER:-}" ]] && args+=("-Dtest=${TEST_FILTER}")

config_error=""
case "${STRATEGY:-}" in
  BASELINE) ;;
  SHUFFLE_ORDER)
    if [[ -z "${SEED:-}" ]]; then config_error="SHUFFLE_ORDER needs SEED"; fi
    # Classes are shuffled for JUnit 4 and 5; methods within a class only for JUnit 5 (ADR 0004).
    args+=(-Dsurefire.runOrder=random "-Dsurefire.runOrder.random.seed=${SEED:-0}"
      '-Djunit.jupiter.testmethod.order.default=org.junit.jupiter.api.MethodOrderer$Random'
      "-Djunit.jupiter.execution.order.random.seed=${SEED:-0}") ;;
  PARALLEL)
    args+=(-Dparallel=classes -DthreadCount=4 # JUnit 4 provider
      -Djunit.jupiter.execution.parallel.enabled=true
      -Djunit.jupiter.execution.parallel.mode.default=concurrent) ;;
  TIMEZONE)
    # The JVM reads its default timezone from TZ; the orchestrator sets it per run.
    if [[ -z "${TZ:-}" ]]; then config_error="TIMEZONE needs TZ"; fi ;;
  # shortcut: CPU_THROTTLE is a CPU limit set on the container, and RANDOM_SEED and ISOLATED are not wired
  # yet, so they run like BASELINE; implement them with the run planner and experiments.
  CPU_THROTTLE | RANDOM_SEED | ISOLATED) ;;
  *) config_error="unknown STRATEGY '${STRATEGY:-}'" ;;
esac

# Old reports from an earlier run in the same workspace would be published as this run's results.
find /workspace -type d -name surefire-reports -prune -exec rm -rf {} +

start_ms=$(date +%s%3N)
if [[ -n "$config_error" ]]; then
  echo "Run not started: $config_error" >&2
  exit_code=2 # still publish, so the hunt does not wait forever for this run (ADR 0002)
else
  # Stops before the Job's activeDeadlineSeconds, so a hanging test is reported as TIMED_OUT, not lost.
  timeout "${RUN_TIMEOUT_SECONDS:-1800}" mvn "${args[@]}"
  exit_code=$?
fi
duration_ms=$(($(date +%s%3N) - start_ms))
echo "Tests finished with exit code $exit_code in ${duration_ms} ms; publishing"

# The container's exit code is the publisher's: non-zero only when results could not be published.
JAVA_HOME=/opt/publisher-jre JAVA_OPTS="-Dorg.slf4j.simpleLogger.defaultLogLevel=warn" \
  exec /opt/publisher/bin/publisher /workspace "$exit_code" "$duration_ms"
