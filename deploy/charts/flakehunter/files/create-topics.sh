#!/usr/bin/env bash
# Creates the topics from tech doc section 6. Safe to rerun: also sets retention on existing topics, so a new
# size preset applies on the next run. Used by Compose (kafka-init) and by the Helm chart (kafka-init Job).
# Env: BOOTSTRAP_SERVERS, TEST_RESULTS_RETENTION_DAYS, EVENTS_RETENTION_DAYS.
set -euo pipefail

create() {
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVERS" --create --if-not-exists \
    --topic "$1" --partitions "$2" --replication-factor 1
  /opt/kafka/bin/kafka-configs.sh --bootstrap-server "$BOOTSTRAP_SERVERS" --alter --entity-type topics \
    --entity-name "$1" --add-config retention.ms="$3" > /dev/null
}

DAY_MS=86400000
RESULTS=$(( ${TEST_RESULTS_RETENTION_DAYS:-3} * DAY_MS ))
EVENTS=$(( ${EVENTS_RETENTION_DAYS:-14} * DAY_MS ))

create hunt.status            3 $EVENTS
create test.results           6 $RESULTS
create run.completed          3 $EVENTS
create analysis.completed     3 $EVENTS
create finding.detected       6 $EVENTS
create experiment.requested   3 $EVENTS
create experiment.completed   3 $EVENTS
create sources.exported       3 $EVENTS
create diagnosis.completed    3 $EVENTS
create diagnosis.updated      3 $EVENTS
create verification.requested 3 $EVENTS
create fix.verified           3 $EVENTS
# Dead letters of the topics consumed so far (tech doc section 11).
for t in hunt.status test.results run.completed analysis.completed finding.detected \
  experiment.requested experiment.completed sources.exported verification.requested fix.verified \
  diagnosis.completed diagnosis.updated; do
  create "$t.dlq" 1 $EVENTS
done
