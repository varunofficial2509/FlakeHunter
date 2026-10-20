package dev.flakehunter.orchestrator.hunt;

import dev.flakehunter.events.EventEnvelope;
import dev.flakehunter.events.RunCompleted;
import dev.flakehunter.events.Topics;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Records each run's outcome as the runner reports it. */
@Component
class RunCompletedListener {

    private static final TypeReference<EventEnvelope<RunCompleted>> TYPE = new TypeReference<>() {};

    private final HuntService hunts;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    RunCompletedListener(HuntService hunts, JdbcClient jdbc, JsonMapper json) {
        this.hunts = hunts;
        this.jdbc = jdbc;
        this.json = json;
    }

    // One transaction: the event is marked processed only if the run update commits too.
    @KafkaListener(topics = Topics.RUN_COMPLETED)
    @Transactional
    void onRunCompleted(String message) {
        EventEnvelope<RunCompleted> event = json.readValue(message, TYPE);
        if (!firstTime(event.eventId())) return; // Kafka redelivered it (at-least-once)
        RunCompleted run = event.payload();
        hunts.finishRun(run.runId(), Run.Status.valueOf(run.status().name()));
    }

    private boolean firstTime(UUID eventId) {
        return jdbc.sql("INSERT INTO hunt.processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING")
                .param(eventId).update() == 1;
    }
}
