package dev.flakehunter.orchestrator.outbox;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copies unpublished outbox rows to Kafka. At-least-once: a crash after a send but before the commit sends
 * that event again, so consumers dedupe on eventId.
 */
// shortcut: with 2+ replicas, SKIP LOCKED can publish one hunt's events out of order across replicas;
// run one replica (local) or add leader election before scaling the orchestrator out.
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH = 100;

    private final OutboxRepository events;
    private final KafkaTemplate<String, String> kafka;

    OutboxRelay(OutboxRepository events, KafkaTemplate<String, String> kafka) {
        this.events = events;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    void publishPending() {
        for (OutboxEvent event : events.lockUnpublished(BATCH)) {
            try {
                // Wait for the broker's ack (acks=all) before marking the row, and keep order within the batch.
                kafka.send(event.getTopic(), event.getKey(), event.getPayload()).get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Stop here so later events don't overtake this one. Rows sent so far still commit as published.
                log.warn("Publishing outbox event {} to {} failed; retrying next poll", event.getId(),
                        event.getTopic(), e);
                return;
            }
            event.markPublished();
        }
    }
}
