package dev.flakehunter.aggregator.outbox;

import dev.flakehunter.events.EventEnvelope;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Records an event to publish. The caller's transaction decides whether it is ever sent. */
// shortcut: a JDBC copy of hunt-orchestrator's JPA outbox; move both into libs/ if a third service needs one.
@Component
public class Outbox {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    Outbox(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // MANDATORY: called outside a transaction, the event could be saved while the findings are not.
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(String topic, String key, UUID huntId, Object payload) {
        EventEnvelope<Object> event = EventEnvelope.of(topic, huntId, payload);
        jdbc.sql("INSERT INTO results.outbox (id, topic, key, payload) VALUES (?, ?, ?, ?::jsonb)")
                .params(event.eventId(), topic, key, json.writeValueAsString(event)).update();
    }
}
