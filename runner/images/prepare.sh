#!/usr/bin/env bash
# Prepares a hunt once (ADR 0003): fetch the commit, resolve dependencies and compile the tests into /workspace.
# Every run of the hunt then copies this workspace and only runs tests.
# Inputs: REPO_URL, COMMIT_SHA, MAVEN_ARGS (read by Maven itself). /workspace is the hunt's volume.
set -euo pipefail
: "${REPO_URL:?}" "${COMMIT_SHA:?}"

cd /workspace
find . -mindepth 1 -delete # a retried prepare starts clean
git init -q .
git remote add origin "$REPO_URL"
# Only the one commit we test: no history, so even big repositories fetch quickly.
git fetch -q --depth 1 origin "$COMMIT_SHA"
git checkout -q FETCH_HEAD

mvn -B -q test-compile -DskipTests
# Download the Surefire version the runs invoke (entrypoint.sh) now, not once per run.
mvn -B -q org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test -DskipTests
echo "Prepared ${REPO_URL}@${COMMIT_SHA}"
