package dev.flakehunter.orchestrator.outbox;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Records an event to publish. The caller's transaction decides whether it is ever sent. */
@Component
public class Outbox {

    private final OutboxRepository events;
    private final JsonMapper json;

    Outbox(OutboxRepository events, JsonMapper json) {
        this.events = events;
        this.json = json;
    }

    /**
     * Wraps the payload in the event envelope (tech doc section 6) and stores it, keyed by huntId so one
     * hunt's events stay in order on one partition.
     */
    // MANDATORY: called outside a transaction, the event could be saved while the state change is not.
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(String topic, UUID huntId, Map<String, ?> payload) {
        UUID eventId = UUID.randomUUID();
        // shortcut: no traceparent until OpenTelemetry lands in Phase 4.
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", topic);
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", Instant.now());
        envelope.put("huntId", huntId);
        envelope.put("payload", payload);
        events.save(new OutboxEvent(eventId, topic, huntId.toString(), json.writeValueAsString(envelope)));
    }
}
