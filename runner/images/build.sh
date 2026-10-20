#!/usr/bin/env bash
# Builds the runner base images flakehunter/runner:jdk8|jdk11|jdk17|jdk21 from tags pinned in deploy/images.env.
# Usage (from the repo root): runner/images/build.sh [8 11 17 21]
set -euo pipefail
cd "$(dirname "$0")/../.."
# shellcheck source=../../deploy/images.env
source deploy/images.env

./gradlew -q :runner:publisher:installDist

jdks=("$@")
[[ ${#jdks[@]} -eq 0 ]] && jdks=(8 11 17 21)
for v in "${jdks[@]}"; do
  image_var="MAVEN_JDK${v}_IMAGE"
  docker build -f runner/images/Dockerfile \
    --build-arg "MAVEN_IMAGE=${!image_var:?no ${image_var} in deploy/images.env}" \
    --build-arg "PUBLISHER_JRE_IMAGE=${JRE_IMAGE}" \
    -t "flakehunter/runner:jdk${v}" runner
done
