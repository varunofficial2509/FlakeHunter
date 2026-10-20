package dev.flakehunter.orchestrator.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.flakehunter.orchestrator.runner.Runner.RunJob;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobConditionBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** Against fabric8's in-memory API server: nothing runs, so each test plays the Job controller itself. */
@EnableKubernetesMockClient(crud = true)
class KubernetesRunnerTests {

    static final String NS = "flakehunter-runners";

    KubernetesClient client; // injected by the mock server extension
    KubernetesRunner runner;

    @BeforeEach
    void setUp() {
        runner = new KubernetesRunner(new RunnerProperties("registry:5000/flakehunter/runner:jdk", null, NS,
                "kafka.flakehunter.svc.cluster.local:9092", "flakehunter-runner-m2", Duration.ofMinutes(15),
                Duration.ofMinutes(30), DataSize.ofMegabytes(1536), DataSize.ofGigabytes(3), DataSize.ofBytes(0)),
                client);
    }

    @Test
    void aRunIsAHardenedJobThatReportsSuccess() {
        UUID runId = UUID.randomUUID();
        CompletableFuture<Integer> exit = start(runJob(runId, "CPU_THROTTLE"));

        Job job = awaitJob("run-" + runId);
        assertThat(job.getSpec().getBackoffLimit()).isZero();
        assertThat(job.getSpec().getActiveDeadlineSeconds()).isEqualTo(Duration.ofMinutes(32).toSeconds());
        assertThat(job.getSpec().getTemplate().getSpec().getAutomountServiceAccountToken()).isFalse();
        Container c = job.getSpec().getTemplate().getSpec().getContainers().getFirst();
        assertThat(c.getImage()).isEqualTo("registry:5000/flakehunter/runner:jdk17");
        assertThat(c.getResources().getLimits())
                .containsEntry("memory", new Quantity("1536Mi")).containsEntry("cpu", new Quantity("250m"));
        assertThat(c.getSecurityContext().getRunAsNonRoot()).isTrue();
        assertThat(c.getVolumeMounts()).anyMatch(m -> m.getMountPath().equals("/prepared") && m.getReadOnly());
        assertThat(c.getEnv()).anyMatch(e -> e.getName().equals("RUN_ID") && e.getValue().equals(runId.toString()));

        finish(job, "Complete", null);
        assertThat(exit.join()).isZero();
    }

    @Test
    void anOomKilledRunReports137() {
        UUID runId = UUID.randomUUID();
        CompletableFuture<Integer> exit = start(runJob(runId, "BASELINE"));
        Job job = awaitJob("run-" + runId);

        client.pods().inNamespace(NS).resource(new PodBuilder()
                .withNewMetadata().withName("run-" + runId + "-x")
                .addToLabels(KubernetesRunner.JOB_NAME_LABEL, "run-" + runId).endMetadata()
                .withNewStatus().withPhase("Failed").addNewContainerStatus().withNewState().withNewTerminated()
                .withExitCode(137).withReason("OOMKilled").endTerminated().endState().endContainerStatus().endStatus()
                .build()).create();
        finish(job, "Failed", "BackoffLimitExceeded");

        assertThat(exit.join()).isEqualTo(Runner.KILLED);
    }

    @Test
    void aJobPastItsDeadlineReportsTimedOut() {
        UUID runId = UUID.randomUUID();
        CompletableFuture<Integer> exit = start(runJob(runId, "BASELINE"));

        finish(awaitJob("run-" + runId), "Failed", "DeadlineExceeded");

        assertThat(exit.join()).isEqualTo(Runner.TIMED_OUT);
    }

    @Test
    void theRunnerQuotaIsTheMachine() {
        client.resourceQuotas().inNamespace(NS).resource(new ResourceQuotaBuilder()
                .withNewMetadata().withName(KubernetesRunner.QUOTA).endMetadata()
                .withNewSpec().addToHard("limits.memory", new Quantity("3Gi")).endSpec().build()).create();

        assertThat(runner.machine().usableBytes()).isEqualTo(3L << 30);
    }

    @Test
    void theMavenCacheStaysWhileARunnerPodIsActive() {
        client.pods().inNamespace(NS).resource(new PodBuilder()
                .withNewMetadata().withName("busy").addToLabels("app.kubernetes.io/name", "flakehunter-runner")
                .endMetadata().withNewStatus().withPhase("Running").endStatus().build()).create();

        assertThat(runner.removeMavenCache()).isFalse();
    }

    private RunJob runJob(UUID runId, String strategy) {
        return new RunJob(UUID.randomUUID(), runId, 17, strategy, null, null, null, DataSize.ofMegabytes(1536));
    }

    private CompletableFuture<Integer> start(RunJob job) {
        return CompletableFuture.supplyAsync(() -> runner.run(job));
    }

    private Job awaitJob(String name) {
        return await().until(() -> client.batch().v1().jobs().inNamespace(NS).withName(name).get(),
                job -> job != null);
    }

    private void finish(Job job, String type, String reason) {
        job.setStatus(null);
        Job withStatus = new JobBuilder(job).withNewStatus()
                .addToConditions(new JobConditionBuilder().withType(type).withStatus("True").withReason(reason).build())
                .endStatus().build();
        client.batch().v1().jobs().inNamespace(NS).resource(withStatus).updateStatus();
    }
}
