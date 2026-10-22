package dev.flakehunter.aggregator.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.flakehunter.aggregator.TestcontainersConfiguration;
import dev.flakehunter.events.DiagnosisCompleted;
import dev.flakehunter.events.EventEnvelope;
import dev.flakehunter.events.RunCompleted;
import dev.flakehunter.events.TestResult;
import dev.flakehunter.events.TestResult.Status;
import dev.flakehunter.events.Topics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

/** Events in through real Kafka, findings out through the outbox and the REST API. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AnalysisFlowTests {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvcTester mvc;

    @Test
    void findsTheTestThatPassesAndFailsAndCompletesTheAnalysis() throws Exception {
        UUID hunt = UUID.randomUUID();
        status(hunt, Instant.now(), Map.of("status", "RUNNING", "plannedRuns", 5));
        status(hunt, Instant.now(), Map.of("status", "ANALYZING"));

        for (int i = 0; i < 3; i++) run(hunt, "BASELINE", Status.PASSED);
        UUID shuffled = run(hunt, "SHUFFLE_ORDER", Status.FAILED);
        result(hunt, shuffled, "SHUFFLE_ORDER", "com.acme.T#flaky", Status.FAILED); // redelivered: counted once
        // A run whose build crashed: its stray result must not count.
        UUID crashed = UUID.randomUUID();
        result(hunt, crashed, "TIMEZONE", "com.acme.T#flaky", Status.FAILED);
        completed(hunt, crashed, "TIMEZONE", RunCompleted.Status.FAILED, 0);

        await().atMost(Duration.ofSeconds(20)).until(() -> published(hunt, Topics.ANALYSIS_COMPLETED) != null);
        assertThat(published(hunt, Topics.ANALYSIS_COMPLETED))
                .isEqualTo(Map.of("runsAnalyzed", 4, "testsAnalyzed", 4, "findings", 1));

        assertThat(mvc.get().uri("/api/findings?huntId=" + hunt)).hasStatusOk().bodyJson()
                .extractingPath("$[*].testId").asArray().containsExactly("com.acme.T#flaky");
        assertThat(mvc.get().uri("/api/findings?huntId=" + hunt)).bodyJson()
                .extractingPath("$[0].evidence.triggeringStrategies").asArray().containsExactly("SHUFFLE_ORDER");
        assertThat(mvc.get().uri("/api/findings?huntId=" + hunt)).bodyJson()
                .extractingPath("$[0].evidence.runs").isEqualTo(4);

        // The agents report on the finding: two updates, the end, then an update that arrives late.
        UUID finding = jdbc.sql("SELECT id FROM results.findings WHERE hunt_id = ?").param(hunt)
                .query(UUID.class).single();
        diagnosis(hunt, Topics.DIAGNOSIS_UPDATED, finding, null, 1);
        diagnosis(hunt, Topics.DIAGNOSIS_UPDATED, finding, null, 2);
        diagnosis(hunt, Topics.DIAGNOSIS_COMPLETED, finding, "FIX_VERIFIED", 3);
        diagnosis(hunt, Topics.DIAGNOSIS_UPDATED, finding, null, 2);
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.sql(
                "SELECT status FROM results.diagnoses WHERE finding_id = ?").param(finding).query(String.class)
                .optional().isPresent());
        Thread.sleep(500); // give the late update time to be (not) applied
        assertThat(mvc.get().uri("/api/findings/" + finding)).bodyJson()
                .extractingPath("$.diagnosis.status").isEqualTo("FIX_VERIFIED");
        assertThat(mvc.get().uri("/api/findings/" + finding)).bodyJson()
                .extractingPath("$.diagnosis.steps").asArray().hasSize(3);
    }

    private void diagnosis(UUID hunt, String topic, UUID finding, String status, int steps) throws Exception {
        List<Map<String, Object>> timeline = IntStream.range(0, steps)
                .mapToObj(i -> Map.<String, Object>of("agent", "investigator", "summary", "step " + i)).toList();
        send(topic, finding.toString(), EventEnvelope.of(topic, hunt,
                new DiagnosisCompleted(finding, status, null, null, null, null, timeline)));
    }

    @Test
    void runnerThatNeverReportedDoesNotBlockTheHuntForever() throws Exception {
        UUID hunt = UUID.randomUUID();
        status(hunt, Instant.now(), Map.of("status", "RUNNING", "plannedRuns", 3));
        // 2 of 3 runs reported, and the orchestrator gave up on the third over a minute ago.
        status(hunt, Instant.now().minus(Duration.ofMinutes(2)), Map.of("status", "ANALYZING"));
        run(hunt, "BASELINE", Status.PASSED);
        run(hunt, "BASELINE", Status.PASSED);

        await().atMost(Duration.ofSeconds(20)).until(() -> published(hunt, Topics.ANALYSIS_COMPLETED) != null);
        assertThat(published(hunt, Topics.ANALYSIS_COMPLETED)).containsEntry("runsAnalyzed", 2);
    }

    @Test
    void unknownFindingIs404() {
        assertThat(mvc.get().uri("/api/findings/" + UUID.randomUUID())).hasStatus(404);
    }

    /** One run of 4 tests: stable passes, broken fails, skipped skips, flaky gets the given status. */
    private UUID run(UUID hunt, String strategy, Status flaky) throws Exception {
        UUID run = UUID.randomUUID();
        result(hunt, run, strategy, "com.acme.T#stable", Status.PASSED);
        result(hunt, run, strategy, "com.acme.T#broken", Status.FAILED);
        result(hunt, run, strategy, "com.acme.T#skipped", Status.SKIPPED);
        result(hunt, run, strategy, "com.acme.T#flaky", flaky);
        completed(hunt, run, strategy, RunCompleted.Status.SUCCEEDED, 4);
        return run;
    }

    private void result(UUID hunt, UUID run, String strategy, String testId, Status status) throws Exception {
        boolean failed = status == Status.FAILED;
        send(Topics.TEST_RESULTS, hunt + ":" + testId, EventEnvelope.of(Topics.TEST_RESULTS, hunt,
                new TestResult(run, testId, strategy, null, status, 5, failed ? "boom" : null,
                        failed ? testId.substring(testId.indexOf('#')) : null, 0)));
    }

    private void completed(UUID hunt, UUID run, String strategy, RunCompleted.Status status, int total)
            throws Exception {
        send(Topics.RUN_COMPLETED, hunt.toString(), EventEnvelope.of(Topics.RUN_COMPLETED, hunt,
                new RunCompleted(run, strategy, null, status, 1, 1000, total, 0, 0, 0, 0, List.of())));
    }

    private void status(UUID hunt, Instant at, Map<String, Object> payload) throws Exception {
        send(Topics.HUNT_STATUS, hunt.toString(),
                new EventEnvelope<>(UUID.randomUUID(), Topics.HUNT_STATUS, 1, at, hunt, payload));
    }

    private void send(String topic, String key, Object event) throws Exception {
        kafka.send(topic, key, json.writeValueAsString(event)).get();
    }

    /** The payload of the hunt's published event on that topic, or null while it isn't published yet. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> published(UUID hunt, String topic) {
        return jdbc.sql("SELECT payload->'payload' FROM results.outbox WHERE topic = ? AND key = ?"
                + " AND published_at IS NOT NULL").params(topic, hunt.toString()).query(String.class).optional()
                .map(p -> (Map<String, Object>) json.readValue(p, Map.class)).orElse(null);
    }
}
