package dev.flakehunter.orchestrator.hunt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.flakehunter.events.AnalysisCompleted;
import dev.flakehunter.events.EventEnvelope;
import dev.flakehunter.events.RunCompleted;
import dev.flakehunter.events.Topics;
import dev.flakehunter.orchestrator.TestcontainersConfiguration;
import dev.flakehunter.orchestrator.project.ProjectClient;
import dev.flakehunter.orchestrator.runner.DockerRunner;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** The saga from POST to COMPLETED, with Docker and project-service replaced by mocks and real Kafka. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class HuntExecutionTests {

    static final String SHA = "e05e9c5e4be580691cc55a59f3256595393203a1";

    @Autowired
    HuntService huntService;

    @Autowired
    HuntRepository hunts;

    @Autowired
    RunRepository runs;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    @MockitoBean
    ProjectClient projects;

    @MockitoBean
    DockerRunner docker;

    @Test
    void preparesRunsAnalyzesAndCompletes() throws Exception {
        givenProject(8);
        when(docker.image(8)).thenReturn("flakehunter/runner:jdk8");
        UUID huntId = start(new Budget(5, 2));

        // Prepared once, then 3 baseline + 2 perturbed runs started, and the hunt waits for their reports.
        await().atMost(Duration.ofSeconds(10)).until(() -> status(huntId) == HuntStatus.RUNNING
                && runsOf(huntId).stream().allMatch(r -> r.getStatus() == Run.Status.RUNNING));
        verify(docker).prepare(any());
        List<Run> planned = runsOf(huntId);
        assertThat(planned).hasSize(5);
        assertThat(hunts.findById(huntId).orElseThrow().getRunnerImage()).isEqualTo("flakehunter/runner:jdk8");

        String duplicate = null;
        for (Run run : planned) {
            String event = runCompleted(huntId, run.getId(), RunCompleted.Status.SUCCEEDED);
            kafka.send(Topics.RUN_COMPLETED, huntId.toString(), event).get();
            duplicate = event;
        }
        kafka.send(Topics.RUN_COMPLETED, huntId.toString(), duplicate).get(); // at-least-once delivery

        await().atMost(Duration.ofSeconds(15)).until(() -> status(huntId) == HuntStatus.ANALYZING);
        assertThat(runsOf(huntId)).allMatch(r -> r.getStatus() == Run.Status.SUCCEEDED);
        verify(docker, timeout(5000)).removeVolume(huntId);

        String analysis = json.writeValueAsString(EventEnvelope.of(Topics.ANALYSIS_COMPLETED, huntId,
                new AnalysisCompleted(5, 21, 1)));
        kafka.send(Topics.ANALYSIS_COMPLETED, huntId.toString(), analysis).get();
        kafka.send(Topics.ANALYSIS_COMPLETED, huntId.toString(), analysis).get(); // duplicate is ignored
        await().atMost(Duration.ofSeconds(15)).until(() -> status(huntId) == HuntStatus.COMPLETED);
    }

    @Test
    void runnerThatExitsWithoutReportingFailsTheRunNotTheHunt() {
        givenProject(8);
        when(docker.run(any())).thenReturn(1);
        UUID huntId = start(new Budget(3, 3));

        // No run.completed will come, so the launcher records the failures and the hunt can still move on.
        await().atMost(Duration.ofSeconds(10)).until(() -> status(huntId) == HuntStatus.ANALYZING);
        assertThat(runsOf(huntId)).allMatch(r -> r.getStatus() == Run.Status.FAILED);
    }

    @Test
    void failedPrepareFailsTheHuntWithAReason() {
        givenProject(8);
        when(docker.prepare(any())).thenReturn(1);
        UUID huntId = start(new Budget(40, 4));

        await().atMost(Duration.ofSeconds(10)).until(() -> status(huntId) == HuntStatus.FAILED);
        assertThat(hunts.findById(huntId).orElseThrow().getFailureReason()).contains("Preparing failed (exit 1)");
        assertThat(runsOf(huntId)).isEmpty();
    }

    @Test
    void projectWithoutJdkFailsBeforeCloning() {
        givenProject(null);
        UUID huntId = start(new Budget(40, 4));

        await().atMost(Duration.ofSeconds(10)).until(() -> status(huntId) == HuntStatus.FAILED);
        assertThat(hunts.findById(huntId).orElseThrow().getFailureReason()).contains("Set jdkVersion");
    }

    private void givenProject(Integer jdk) {
        when(projects.find(any())).thenAnswer(call -> Optional.of(new ProjectClient.Project(
                call.getArgument(0), "https://github.com/alibaba/fastjson", "MAVEN", jdk, null)));
    }

    private UUID start(Budget budget) {
        return huntService.start(new CreateHuntRequest(UUID.randomUUID(), SHA, budget), budget).orElseThrow().getId();
    }

    private HuntStatus status(UUID huntId) {
        return hunts.findById(huntId).orElseThrow().getStatus();
    }

    private List<Run> runsOf(UUID huntId) {
        return runs.findAll().stream().filter(r -> r.getHuntId().equals(huntId)).toList();
    }

    private String runCompleted(UUID huntId, UUID runId, RunCompleted.Status status) {
        return json.writeValueAsString(EventEnvelope.of(Topics.RUN_COMPLETED, huntId,
                new RunCompleted(runId, "BASELINE", null, status, 0, 1000, 21, 21, 0, 0, 0)));
    }
}
