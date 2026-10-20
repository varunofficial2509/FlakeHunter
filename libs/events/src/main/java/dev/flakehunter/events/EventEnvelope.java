package dev.flakehunter.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The wrapper around every Kafka event (tech doc section 6). Consumers dedupe on {@code eventId}.
 * shortcut: no traceparent until OpenTelemetry lands in Phase 4.
 */
public record EventEnvelope<T>(
        UUID eventId, String eventType, int schemaVersion, Instant occurredAt, UUID huntId, T payload) {

    public static <T> EventEnvelope<T> of(String eventType, UUID huntId, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), eventType, 1, Instant.now(), huntId, payload);
    }
}
