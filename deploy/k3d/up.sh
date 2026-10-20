#!/usr/bin/env bash
# Creates the k3d cluster if needed, builds and pushes every image, and installs or upgrades the Helm chart.
# Rerun after any code change. The first run needs the login password:
#   FLAKEHUNTER_ADMIN_PASSWORD=pick-one deploy/k3d/up.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a
source deploy/images.env
set +a

CONTEXT=k3d-flakehunter # every kubectl/helm call names it, so nothing lands on another cluster
NS=flakehunter
REGISTRY=localhost:5001
TAG=$(date +%Y%m%d%H%M%S) # new tag each run, so Kubernetes rolls out the new images
SERVICES=(project-service hunt-orchestrator result-aggregator api-gateway)

if ! k3d cluster list flakehunter >/dev/null 2>&1; then
  k3d cluster create --config deploy/k3d/cluster.yaml --image "$K3S_IMAGE"
fi

./gradlew --quiet bootJar
for s in "${SERVICES[@]}"; do
  docker build --quiet -f deploy/docker/service.Dockerfile --build-arg JRE_IMAGE --build-arg SERVICE="$s" \
    -t "$REGISTRY/flakehunter/$s:$TAG" .
  docker push --quiet "$REGISTRY/flakehunter/$s:$TAG"
done
docker build --quiet --build-arg NODE_IMAGE --build-arg NGINX_IMAGE -t "$REGISTRY/flakehunter/web:$TAG" web
docker push --quiet "$REGISTRY/flakehunter/web:$TAG"

# Runner images keep their jdkN tags (Jobs pull them with imagePullPolicy Always). Building all four takes a
# while the first time; RUNNER_JDKS="17" deploy/k3d/up.sh builds only what your projects use.
read -r -a jdks <<< "${RUNNER_JDKS:-8 11 17 21}"
runner/images/build.sh "${jdks[@]}"
for v in "${jdks[@]}"; do
  docker tag "flakehunter/runner:jdk$v" "$REGISTRY/flakehunter/runner:jdk$v"
  docker push --quiet "$REGISTRY/flakehunter/runner:jdk$v"
done

kubectl --context "$CONTEXT" create namespace "$NS" --dry-run=client -o yaml | kubectl --context "$CONTEXT" apply -f -
# Secrets are made once and kept: a new Postgres password would lock the services out of the existing data.
if ! kubectl --context "$CONTEXT" -n "$NS" get secret flakehunter-secrets >/dev/null 2>&1; then
  : "${FLAKEHUNTER_ADMIN_PASSWORD:?first run: set FLAKEHUNTER_ADMIN_PASSWORD}"
  kubectl --context "$CONTEXT" -n "$NS" create secret generic flakehunter-secrets \
    --from-literal=admin-password="$FLAKEHUNTER_ADMIN_PASSWORD" \
    --from-literal=jwt-secret="$(openssl rand -base64 48)" \
    --from-literal=postgres-password="$(openssl rand -hex 24)"
fi

# KEDA first: our chart's ScaledObject needs its CRDs.
helm upgrade --install keda keda --repo https://kedacore.github.io/charts --version "$KEDA_CHART_VERSION" \
  --kube-context "$CONTEXT" -n keda --create-namespace --wait -f deploy/k3d/keda-values.yaml

helm upgrade --install flakehunter deploy/charts/flakehunter --kube-context "$CONTEXT" -n "$NS" \
  --wait --timeout 10m \
  --set image.tag="$TAG" --set postgres.image="$POSTGRES_IMAGE" --set kafka.image="$KAFKA_IMAGE" \
  --set prometheus.image="$PROMETHEUS_IMAGE" --set grafana.image="$GRAFANA_IMAGE"

echo "FlakeHunter is up: http://localhost:8090 (user admin), Grafana: http://localhost:8090/grafana"
