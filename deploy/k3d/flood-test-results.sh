#!/usr/bin/env bash
# Floods test.results with fake results so you can watch KEDA scale result-aggregator in Grafana
# (http://localhost:8090/grafana: consumer lag, then replicas). Usage: deploy/k3d/flood-test-results.sh [count]
# Every fake result belongs to one made-up hunt, so the DELETE printed at the end removes them all.
set -euo pipefail
cd "$(dirname "$0")/../.."
source deploy/images.env
COUNT=${1:-2000000}
HUNT=00000000-0000-0000-0000-00000000f100

# Its own pod, not kafka-0: a second JVM next to the broker could push it past its memory limit.
kubectl --context k3d-flakehunter -n flakehunter run flood-test-results --rm -i --restart=Never \
  --image "$KAFKA_IMAGE" --env HUNT="$HUNT" --env COUNT="$COUNT" --command -- sh -c '
    # 1,000 distinct results; the producer picks one at random for each message.
    i=0
    while [ $i -lt 1000 ]; do
      printf "{\"eventId\":\"00000000-0000-0000-0000-%012d\",\"eventType\":\"test.results\",\"schemaVersion\":1,\"occurredAt\":\"2026-01-01T00:00:00Z\",\"huntId\":\"%s\",\"payload\":{\"runId\":\"%s\",\"testId\":\"Flood#test%d\",\"strategy\":\"BASELINE\",\"seed\":null,\"status\":\"PASSED\",\"durationMs\":1,\"failureMessage\":null,\"stackHash\":null,\"orderIndex\":%d}}\n" \
        $i "$HUNT" "$HUNT" $i $i
      i=$((i + 1))
    done > /tmp/results.json
    /opt/kafka/bin/kafka-producer-perf-test.sh --topic test.results --num-records "$COUNT" --throughput -1 \
      --payload-file /tmp/results.json \
      --producer-props bootstrap.servers=kafka.flakehunter.svc.cluster.local:9092 acks=1 linger.ms=20'

echo "Remove the fake results afterwards:"
echo "  kubectl --context k3d-flakehunter -n flakehunter exec postgres-0 -- psql -U flakehunter -c \"DELETE FROM results.test_results WHERE hunt_id = '$HUNT'\""
