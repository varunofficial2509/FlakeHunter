package dev.flakehunter.orchestrator.hunt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import dev.flakehunter.events.EventEnvelope;
import dev.flakehunter.events.ExperimentRequested;
import dev.flakehunter.events.RunCompleted;
import dev.flakehunter.events.Topics;
import dev.flakehunter.orchestrator.TestcontainersConfiguration;
import dev.flakehunter.orchestrator.project.ProjectClient;
import dev.flakehunter.orchestrator.runner.Runner;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** experiment.requested → runs in the experiment's own workspace → experiment.completed, with real Kafka. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExperimentTests {

    static final String TEST = "com.alibaba.json.bvt.date.DateTest4_indian#test_now";

    @Autowired
    HuntRepository hunts;

    @Autowired
    RunRepository runs;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    Runner runners;

    @MockitoBean
    ProjectClient projects;

    @Test
    void runsTheExperimentOnceAndCountsFailuresOfTheRequestedTest() throws Exception {
        Hunt hunt = completedHunt();
        UUID findingId = UUID.randomUUID();
        UUID experimentId = UUID.randomUUID();
        String request = request(hunt, findingId, experimentId, new ExperimentRequested.Plan(
                List.of(TEST), "TIMEZONE", "Asia/Kolkata", 3));
        kafka.send(Topics.EXPERIMENT_REQUESTED, findingId.toString(), request).get();
        kafka.send(Topics.EXPERIMENT_REQUESTED, findingId.toString(), request).get(); // the agent asked again

        await().atMost(Duration.ofSeconds(15)).until(() -> runsOf(experimentId).size() == 3
                && runsOf(experimentId).stream().allMatch(r -> r.getStatus() == Run.Status.RUNNING));
        verify(runners).prepare(argThat(job -> job.huntId().equals(experimentId))); // its own workspace
        verify(runners, timeout(5000).times(3)).run(argThat(job -> job.huntId().equals(experimentId)
                && TEST.equals(job.testFilter()) && "Asia/Kolkata".equals(job.timezone())));

        // Two runs fail the test (one of them twice over), one passes.
        List<Run> planned = runsOf(experimentId);
        report(experimentId, planned.get(0), List.of(TEST));
        report(experimentId, planned.get(1), List.of(TEST, "com.other.Test#x"));
        report(experimentId, planned.get(2), List.of());

        JsonNode answer = await().atMost(Duration.ofSeconds(15)).until(() -> answer(experimentId), a -> a != null);
        assertThat(answer.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(answer.get("runs").asInt()).isEqualTo(3);
        assertThat(answer.get("tests").get(0).get("failures").asInt()).isEqualTo(2);
        assertThat(hunts.findById(hunt.getId()).orElseThrow().getStatus()).isEqualTo(HuntStatus.COMPLETED);
        verify(runners, timeout(5000)).removeVolume(experimentId);
    }

    @Test
    void rejectsAPlanThatBreaksTheRules() throws Exception {
        Hunt hunt = completedHunt();
        UUID findingId = UUID.randomUUID();
        UUID experimentId = UUID.randomUUID();
        // A test "id" that would smuggle more into Surefire's -Dtest.
        kafka.send(Topics.EXPERIMENT_REQUESTED, findingId.toString(), request(hunt, findingId, experimentId,
                new ExperimentRequested.Plan(List.of("a.B#c,**/*"), "BASELINE", null, 5))).get();

        JsonNode answer = await().atMost(Duration.ofSeconds(15)).until(() -> answer(experimentId), a -> a != null);
        assertThat(answer.get("status").asString()).isEqualTo("REJECTED");
        assertThat(answer.get("reason").asString()).contains("Not a test id");
        assertThat(runsOf(experimentId)).isEmpty();
        verify(runners, timeout(1000).times(0)).prepare(argThat(job -> job.huntId().equals(experimentId)));
    }

    private Hunt completedHunt() {
        Hunt hunt = new Hunt(UUID.randomUUID(), HuntExecutionTests.SHA, new Budget(3, 2),
                new ProjectClient.Project(UUID.randomUUID(), "https://github.com/alibaba/fastjson", "MAVEN", 8, null));
        for (HuntStatus next : List.of(HuntStatus.PREPARING, HuntStatus.RUNNING, HuntStatus.ANALYZING,
                HuntStatus.COMPLETED)) {
            hunt.moveTo(next);
        }
        return hunts.save(hunt);
    }

    private String request(Hunt hunt, UUID findingId, UUID experimentId, ExperimentRequested.Plan plan) {
        return json.writeValueAsString(EventEnvelope.of(Topics.EXPERIMENT_REQUESTED, hunt.getId(),
                new ExperimentRequested(findingId, experimentId, "fails in IST", plan)));
    }

    private void report(UUID experimentId, Run run, List<String> failed) throws Exception {
        String event = json.writeValueAsString(EventEnvelope.of(Topics.RUN_COMPLETED, experimentId,
                new RunCompleted(run.getId(), "TIMEZONE", null, RunCompleted.Status.SUCCEEDED, failed.isEmpty() ? 0 : 1,
                        1000, 1, 1 - Math.min(1, failed.size()), failed.size(), 0, 0, failed)));
        kafka.send(Topics.RUN_COMPLETED, experimentId.toString(), event).get();
    }

    private List<Run> runsOf(UUID experimentId) {
        return runs.findByExperimentId(experimentId);
    }

    private JsonNode answer(UUID experimentId) {
        return jdbc.sql("SELECT payload FROM hunt.outbox WHERE topic = ?").param(Topics.EXPERIMENT_COMPLETED)
                .query(String.class).list().stream().map(p -> json.readTree(p).get("payload"))
                .filter(p -> p.get("experimentId").asString().equals(experimentId.toString()))
                .findFirst().orElse(null);
    }
}
