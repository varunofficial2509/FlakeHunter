package dev.flakehunter.orchestrator.hunt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.flakehunter.events.AnalysisCompleted;
import dev.flakehunter.events.EventEnvelope;
import dev.flakehunter.events.RunCompleted;
import dev.flakehunter.events.Topics;
import dev.flakehunter.orchestrator.TestcontainersConfiguration;
import dev.flakehunter.orchestrator.project.ProjectClient;
import dev.flakehunter.orchestrator.runner.Runner;
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

/** The saga from POST to the end of the analysis, with Docker and project-service replaced by mocks and real Kafka. */
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

    @Autowired
    HuntExecution execution;

    @MockitoBean
    ProjectClient projects;

    @MockitoBean
    Runner runners;

    @Test
    void preparesRunsAnalyzesAndCompletes() throws Exception {
        givenProject(8);
        when(runners.image(8)).thenReturn("flakehunter/runner:jdk8");
        UUID huntId = start(new Budget(5, 2));

        // Prepared once, then 3 baseline + 2 perturbed runs started, and the hunt waits for their reports.
        await().atMost(Duration.ofSeconds(10)).until(() -> status(huntId) == HuntStatus.RUNNING
                && runsOf(huntId).stream().allMatch(r -> r.getStatus() == Run.Status.RUNNING));
        verify(runners).prepare(any());
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
        verify(runners, timeout(5000)).removeVolume(huntId);

        String analysis = json.writeValueAsString(EventEnvelope.of(Topics.ANALYSIS_COMPLETED, huntId,
                new AnalysisCompleted(5, 21, 1)));
        kafka.send(Topics.ANALYSIS_COMPLETED, huntId.toString(), analysis).get();
        kafka.send(Topics.ANALYSIS_COMPLETED, huntId.toString(), analysis).get(); // duplicate is ignored
        // Tests run like Compose, without agents, so its finding waits for AI (AiSwitchTests has the rest).
        await().atMost(Duration.ofSeconds(15)).until(() -> status(huntId) == HuntStatus.AWAITING_AI);
    }

    @Test
    void runnerThatExitsWithoutReportingFailsTheRunNotTheHunt() {
        givenProject(8);
        when(runners.run(any())).thenReturn(1);
        UUID huntId = start(new Budget(3, 3));

        // No run.completed will come, so the launcher records the failures and the hunt can still move on.
        await().atMost(Duration.ofSeconds(10)).until(() -> status(huntId) == HuntStatus.ANALYZING);
        assertThat(runsOf(huntId)).allMatch(r -> r.getStatus() == Run.Status.FAILED);
    }

    @Test
    void failedPrepareFailsTheHuntWithAReason() {
        givenProject(8);
        when(runners.prepare(any())).thenReturn(1);
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

    @Test
    void aRestartResumesHuntsWhereTheyStopped() {
        when(runners.run(any())).thenReturn(1);
        // What a crashed orchestrator leaves behind: one run in a live container, one never started, one done.
        Hunt running = hunts.save(huntIn(HuntStatus.RUNNING));
        Run active = new Run(running.getId(), Strategy.BASELINE, null, null);
        active.start();
        Run waiting = new Run(running.getId(), Strategy.SHUFFLE_ORDER, 7L, null);
        Run done = new Run(running.getId(), Strategy.BASELINE, null, null);
        done.finish(Run.Status.SUCCEEDED, null);
        runs.saveAll(List.of(active, waiting, done));
        Hunt preparing = hunts.save(huntIn(HuntStatus.PREPARING));

        execution.resumeUnfinished();

        await().atMost(Duration.ofSeconds(10)).until(() -> status(running.getId()) == HuntStatus.ANALYZING
                && status(preparing.getId()) == HuntStatus.ANALYZING);
        verify(runners).run(argThat(job -> job.runId().equals(active.getId())));
        verify(runners).run(argThat(job -> job.runId().equals(waiting.getId())));
        verify(runners, never()).run(argThat(job -> job.runId().equals(done.getId())));
        verify(runners).prepare(argThat(job -> job.huntId().equals(preparing.getId())));
        assertThat(runs.findById(done.getId()).orElseThrow().getStatus()).isEqualTo(Run.Status.SUCCEEDED);
    }

    private Hunt huntIn(HuntStatus status) {
        Hunt hunt = new Hunt(UUID.randomUUID(), SHA, new Budget(3, 3), project(UUID.randomUUID(), 8));
        hunt.moveTo(HuntStatus.PREPARING);
        if (status == HuntStatus.RUNNING) hunt.moveTo(HuntStatus.RUNNING);
        return hunt;
    }

    private static ProjectClient.Project project(UUID id, Integer jdk) {
        return new ProjectClient.Project(id, "https://github.com/alibaba/fastjson", "MAVEN", jdk, null);
    }

    private void givenProject(Integer jdk) {
        when(projects.find(any())).thenAnswer(call -> Optional.of(project(call.getArgument(0), jdk)));
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
                new RunCompleted(runId, "BASELINE", null, status, 0, 1000, 21, 21, 0, 0, 0, List.of())));
    }
}
