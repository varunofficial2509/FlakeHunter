package dev.flakehunter.orchestrator.hunt;

import dev.flakehunter.events.AnalysisCompleted;
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

/** Moves hunts along as runners and result-aggregator report back (the saga's inputs). */
@Component
class HuntEventsListener {

    private static final TypeReference<EventEnvelope<RunCompleted>> RUN = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<AnalysisCompleted>> ANALYSIS = new TypeReference<>() {};

    private final HuntService hunts;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    HuntEventsListener(HuntService hunts, JdbcClient jdbc, JsonMapper json) {
        this.hunts = hunts;
        this.jdbc = jdbc;
        this.json = json;
    }

    // One transaction: the event is marked processed only if the run update commits too.
    @KafkaListener(topics = Topics.RUN_COMPLETED)
    @Transactional
    void onRunCompleted(String message) {
        EventEnvelope<RunCompleted> event = json.readValue(message, RUN);
        if (!firstTime(event.eventId())) return; // Kafka redelivered it (at-least-once)
        RunCompleted run = event.payload();
        hunts.finishRun(run.runId(), Run.Status.valueOf(run.status().name()));
    }

    @KafkaListener(topics = Topics.ANALYSIS_COMPLETED)
    @Transactional
    void onAnalysisCompleted(String message) {
        EventEnvelope<AnalysisCompleted> event = json.readValue(message, ANALYSIS);
        if (!firstTime(event.eventId())) return;
        hunts.completeAnalysis(event.huntId(), event.payload().findings());
    }

    private boolean firstTime(UUID eventId) {
        return jdbc.sql("INSERT INTO hunt.processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING")
                .param(eventId).update() == 1;
    }
}
