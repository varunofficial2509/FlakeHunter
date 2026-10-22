package dev.flakehunter.orchestrator.hunt;

import dev.flakehunter.events.AnalysisCompleted;
import dev.flakehunter.events.DiagnosisCompleted;
import dev.flakehunter.events.EventEnvelope;
import dev.flakehunter.events.ExperimentRequested;
import dev.flakehunter.events.RunCompleted;
import dev.flakehunter.events.SourcesExported;
import dev.flakehunter.events.Topics;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Moves hunts and experiments along as runners, result-aggregator and agents report in (the saga's inputs). */
@Component
class HuntEventsListener {

    private static final TypeReference<EventEnvelope<RunCompleted>> RUN = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<AnalysisCompleted>> ANALYSIS = new TypeReference<>() {};

    private static final TypeReference<EventEnvelope<ExperimentRequested>> EXPERIMENT = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<SourcesExported>> SOURCES = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<DiagnosisCompleted>> DIAGNOSIS = new TypeReference<>() {};

    private final HuntService hunts;
    private final Experiments experiments;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    HuntEventsListener(HuntService hunts, Experiments experiments, JdbcClient jdbc, JsonMapper json) {
        this.hunts = hunts;
        this.experiments = experiments;
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
        hunts.finishRun(run.runId(), Run.Status.valueOf(run.status().name()), run.failedTests());
    }

    @KafkaListener(topics = Topics.ANALYSIS_COMPLETED)
    @Transactional
    void onAnalysisCompleted(String message) {
        EventEnvelope<AnalysisCompleted> event = json.readValue(message, ANALYSIS);
        if (!firstTime(event.eventId())) return;
        hunts.completeAnalysis(event.huntId(), event.payload().findings());
    }

    // No processed_events check: the agent may resend one experimentId in a new event; accept() dedupes on it.
    @KafkaListener(topics = Topics.EXPERIMENT_REQUESTED)
    void onExperimentRequested(String message) {
        EventEnvelope<ExperimentRequested> event = json.readValue(message, EXPERIMENT);
        experiments.accept(event.huntId(), event.payload(), false);
    }

    @KafkaListener(topics = Topics.VERIFICATION_REQUESTED)
    void onVerificationRequested(String message) {
        EventEnvelope<ExperimentRequested> event = json.readValue(message, EXPERIMENT);
        experiments.accept(event.huntId(), event.payload(), true);
    }

    // Idempotent on its own: the first export of an experiment is kept.
    @KafkaListener(topics = Topics.SOURCES_EXPORTED)
    void onSourcesExported(String message) {
        SourcesExported sources = json.readValue(message, SOURCES).payload();
        experiments.exported(sources.experimentId(), sources.files());
    }

    // Idempotent on its own: one diagnoses row per finding.
    @KafkaListener(topics = Topics.DIAGNOSIS_COMPLETED)
    void onDiagnosisCompleted(String message) {
        EventEnvelope<DiagnosisCompleted> event = json.readValue(message, DIAGNOSIS);
        hunts.diagnosed(event.huntId(), event.payload().findingId(), event.payload().status());
    }

    private boolean firstTime(UUID eventId) {
        return jdbc.sql("INSERT INTO hunt.processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING")
                .param(eventId).update() == 1;
    }
}
