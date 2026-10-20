package dev.flakehunter.aggregator.outbox;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Copies unpublished outbox rows to Kafka, in order. At-least-once, so consumers dedupe on eventId. */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record Row(UUID id, String topic, String key, String payload) {}

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;

    OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka) {
        this.jdbc = jdbc;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    void publishPending() {
        List<Row> rows = jdbc.sql("""
                SELECT id, topic, key, payload::text AS payload FROM results.outbox WHERE published_at IS NULL
                ORDER BY seq LIMIT 100 FOR UPDATE SKIP LOCKED""").query(Row.class).list();
        for (Row row : rows) {
            try {
                kafka.send(row.topic(), row.key(), row.payload()).get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Stop so later events don't overtake this one; rows sent so far still commit as published.
                log.warn("Publishing outbox event {} to {} failed; retrying next poll", row.id(), row.topic(), e);
                return;
            }
            jdbc.sql("UPDATE results.outbox SET published_at = now() WHERE id = ?").param(row.id()).update();
        }
    }
}
