package dev.flakehunter.orchestrator.outbox;

import dev.flakehunter.events.EventEnvelope;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import java.util.HashMap;
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

    /** Wraps the payload in the event envelope and stores it, keyed by huntId so one hunt's events stay in order. */
    // MANDATORY: called outside a transaction, the event could be saved while the state change is not.
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(String topic, UUID huntId, Object payload) {
        add(topic, huntId, huntId, payload);
    }

    /** Same, with another key, e.g. findingId for agent events (tech doc section 6). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(String topic, UUID huntId, UUID key, Object payload) {
        EventEnvelope<Object> event = EventEnvelope.of(topic, huntId, payload);
        events.save(new OutboxEvent(event.eventId(), topic, key.toString(), json.writeValueAsString(event),
                traceparent()));
    }

    /** The current trace as a W3C traceparent header value, or null outside a trace (e.g. in tests). */
    private static String traceparent() {
        Map<String, String> headers = new HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), headers, Map::put);
        return headers.get("traceparent");
    }
}
