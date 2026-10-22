#!/usr/bin/env bash
# Prepares a hunt once (ADR 0003): fetch the commit, resolve dependencies and compile the tests into /workspace.
# Every run of the hunt then copies this workspace and only runs tests.
# Inputs: REPO_URL, COMMIT_SHA, MAVEN_ARGS (read by Maven itself). /workspace is the hunt's volume.
# Experiments also pass EXPORT_TESTS, HUNT_ID (the experiment's id) and KAFKA_BOOTSTRAP_SERVERS: the tests' source
# files go to the agents. Verifications pass PATCH, the fix to test, applied before compiling.
set -euo pipefail
: "${REPO_URL:?}" "${COMMIT_SHA:?}"

cd /workspace
find . -mindepth 1 -delete # a retried prepare starts clean
git init -q .
git remote add origin "$REPO_URL"
# Only the one commit we test: no history, so even big repositories fetch quickly.
git fetch -q --depth 1 origin "$COMMIT_SHA"
git checkout -q FETCH_HEAD
if [[ -n "${PATCH:-}" ]]; then
  # Exit 3 tells the orchestrator the patch, not the commit, is the problem.
  printf '%s' "$PATCH" | git apply -p1 --whitespace=nowarn - || { echo "The patch does not apply" >&2; exit 3; }
fi

mvn -B -q test-compile -DskipTests
# Download the Surefire version the runs invoke (entrypoint.sh) now, not once per run.
mvn -B -q org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test -DskipTests
if [[ -n "${EXPORT_TESTS:-}" ]]; then
  # The experiment can run without them, so a failed export only costs the agents their source view.
  JAVA_HOME=/opt/publisher-jre JAVA_OPTS="-Dorg.slf4j.simpleLogger.defaultLogLevel=warn" \
    /opt/publisher/bin/publisher --sources /workspace || echo "Exporting sources failed" >&2
fi
echo "Prepared ${REPO_URL}@${COMMIT_SHA}"
