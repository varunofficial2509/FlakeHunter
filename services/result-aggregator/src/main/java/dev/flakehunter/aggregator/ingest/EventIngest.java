package dev.flakehunter.aggregator.ingest;

import dev.flakehunter.events.EventEnvelope;
import dev.flakehunter.events.RunCompleted;
import dev.flakehunter.events.TestResult;
import dev.flakehunter.events.Topics;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores what the runners and the orchestrator report. Every write is idempotent on a natural key, so a
 * redelivered event changes nothing and no processed_events table is needed.
 */
@Component
class EventIngest {

    private static final TypeReference<EventEnvelope<TestResult>> RESULT = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<RunCompleted>> RUN = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<JsonNode>> STATUS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JdbcTemplate batch;
    private final JsonMapper json;
    private final DeadLetterPublishingRecoverer deadLetters;

    EventIngest(JdbcClient jdbc, JdbcTemplate batch, JsonMapper json, DeadLetterPublishingRecoverer deadLetters) {
        this.jdbc = jdbc;
        this.batch = batch;
        this.json = json;
        this.deadLetters = deadLetters;
    }

    // A hunt sends (tests x runs) results, often 100k+; one insert batch per poll instead of a commit per row.
    @KafkaListener(topics = Topics.TEST_RESULTS, batch = "true")
    @Transactional
    void onTestResults(List<ConsumerRecord<String, String>> records) {
        List<Object[]> rows = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            EventEnvelope<TestResult> e;
            try {
                e = json.readValue(record.value(), RESULT);
            } catch (JacksonException ex) {
                // Park only the unreadable one; failing the batch would send the good results to the DLQ too.
                deadLetters.accept(record, ex);
                continue;
            }
            TestResult r = e.payload();
            rows.add(new Object[] {r.runId(), r.testId(), e.huntId(), r.strategy(), r.seed(), r.status().name(),
                r.durationMs(), r.failureMessage(), r.stackHash(), r.orderIndex()});
        }
        batch.batchUpdate("""
                INSERT INTO results.test_results (run_id, test_id, hunt_id, strategy, seed, status, duration_ms,
                    failure_message, stack_hash, order_index)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""", rows);
    }

    @KafkaListener(topics = Topics.RUN_COMPLETED)
    void onRunCompleted(String message) {
        EventEnvelope<RunCompleted> event = json.readValue(message, RUN);
        RunCompleted run = event.payload();
        jdbc.sql("""
                INSERT INTO results.runs (id, hunt_id, strategy, status, total) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING""")
                .params(run.runId(), event.huntId(), run.strategy(), run.status().name(), run.total()).update();
    }

    @KafkaListener(topics = Topics.HUNT_STATUS)
    void onHuntStatus(String message) {
        EventEnvelope<JsonNode> event = json.readValue(message, STATUS);
        switch (event.payload().path("status").asString()) {
            case "RUNNING" -> jdbc.sql("""
                    INSERT INTO results.hunts (id, planned_runs) VALUES (?, ?)
                    ON CONFLICT (id) DO UPDATE SET planned_runs = EXCLUDED.planned_runs""")
                    .params(event.huntId(), event.payload().path("plannedRuns").asInt()).update();
            // Keep the first time: a redelivered ANALYZING must not restart Analyzer's wait.
            case "ANALYZING" -> jdbc.sql("""
                    INSERT INTO results.hunts (id, runs_finished_at) VALUES (?, ?)
                    ON CONFLICT (id) DO UPDATE SET runs_finished_at = coalesce(hunts.runs_finished_at, EXCLUDED.runs_finished_at)
                    """)
                    .params(event.huntId(), Timestamp.from(event.occurredAt())).update();
            default -> {} // other states don't change what gets analyzed
        }
    }
}
