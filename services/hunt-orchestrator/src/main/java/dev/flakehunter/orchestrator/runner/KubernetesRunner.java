package dev.flakehunter.orchestrator.runner;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * Starts runner containers as Kubernetes Jobs in their own namespace (Phase 2). The namespace's ResourceQuota
 * caps their memory, its NetworkPolicy lets them reach only Kafka and the internet, and the chart's Role lets
 * this service touch nothing but Jobs, Pods and PersistentVolumeClaims there.
 */
@Component
@ConditionalOnProperty(name = "flakehunter.runner.platform", havingValue = "kubernetes")
public class KubernetesRunner implements Runner, AutoCloseable {

    /** The chart's ResourceQuota in the runner namespace. */
    static final String QUOTA = "runners";
    static final String JOB_NAME_LABEL = "batch.kubernetes.io/job-name"; // set on its pods by the Job controller

    private static final Logger log = LoggerFactory.getLogger(KubernetesRunner.class);
    private static final Map<String, String> LABELS = Map.of("app.kubernetes.io/name", "flakehunter-runner");
    // Finished Jobs and their pods stay an hour for kubectl logs, then Kubernetes deletes them.
    private static final int KEEP_FINISHED_SECONDS = 3600;
    // Kubernetes ends a Job at its deadline; we wait this much longer before giving up on it ourselves.
    private static final Duration WAIT_MARGIN = Duration.ofMinutes(1);

    private final RunnerProperties props;
    private final KubernetesClient client;
    private final String namespace;

    @Autowired
    KubernetesRunner(RunnerProperties props) {
        this(props, new KubernetesClientBuilder().build()); // in a pod: its ServiceAccount token
    }

    KubernetesRunner(RunnerProperties props, KubernetesClient client) {
        this.props = props;
        this.client = client;
        this.namespace = props.namespace();
    }

    @Override
    public String image(int jdk) {
        return props.imagePrefix() + jdk;
    }

    /** The runners' ResourceQuota is their whole machine: Kafka, Postgres and the services live outside it. */
    @Override
    public Machine machine() {
        ResourceQuota quota;
        try {
            quota = client.resourceQuotas().inNamespace(namespace).withName(QUOTA).get();
        } catch (KubernetesClientException e) {
            throw new RunnerUnavailableException("Kubernetes is not reachable", "Could not read the runner quota", e);
        }
        Quantity memory = quota == null ? null : quota.getSpec().getHard().get("limits.memory");
        if (memory == null) {
            throw new RunnerUnavailableException("No runner quota in namespace " + namespace + "; reinstall the chart",
                    "ResourceQuota " + QUOTA + " with limits.memory not found", null);
        }
        // The pod has no CPU limit, so the JVM sees every CPU of the node.
        return new Machine(memory.getNumericalAmount().longValue(), Runtime.getRuntime().availableProcessors(), 0);
    }

    @Override
    public int prepare(PrepareJob job) {
        ensureClaim(props.mavenCacheVolume());
        ensureClaim(workspace(job.huntId()));
        Container container = container(image(job.jdk()), job.memory(), null, List.of(
                new EnvVar("REPO_URL", job.repoUrl(), null),
                new EnvVar("COMMIT_SHA", job.commitSha(), null),
                new EnvVar("MAVEN_ARGS", nullToEmpty(job.mavenArgs()), null)),
                List.of(mount("workspace", "/workspace", false), mount("m2", "/home/runner/.m2", false)));
        container.setCommand(List.of("/opt/flakehunter/prepare.sh"));
        return execute("prepare-" + job.huntId(), container, job.huntId(), props.prepareTimeout());
    }

    @Override
    public int run(RunJob job) {
        List<EnvVar> env = new ArrayList<>(List.of(
                new EnvVar("HUNT_ID", job.huntId().toString(), null),
                new EnvVar("RUN_ID", job.runId().toString(), null),
                new EnvVar("STRATEGY", job.strategy(), null),
                new EnvVar("MAVEN_ARGS", nullToEmpty(job.mavenArgs()), null),
                new EnvVar("KAFKA_BOOTSTRAP_SERVERS", props.kafkaBootstrapServers(), null),
                new EnvVar("RUN_TIMEOUT_SECONDS", String.valueOf(props.runTimeout().toSeconds()), null)));
        if (job.seed() != null) env.add(new EnvVar("SEED", job.seed().toString(), null));
        if (job.timezone() != null) env.add(new EnvVar("TZ", job.timezone(), null));
        // Starved CPU exposes tests that assume work finishes within a fixed time (tech doc section 9).
        String cpuLimit = job.strategy().equals("CPU_THROTTLE") ? "250m" : null;
        Container container = container(image(job.jdk()), job.memory(), cpuLimit, env, List.of(
                // Read-only: each run copies the workspace, so runs can't change each other's files.
                mount("workspace", "/prepared", true), mount("m2", "/home/runner/.m2", false)));
        return execute("run-" + job.runId(), container, job.huntId(), props.runTimeout().plus(PUBLISH_MARGIN));
    }

    @Override
    public void removeVolume(UUID huntId) {
        client.persistentVolumeClaims().inNamespace(namespace).withName(workspace(huntId)).delete();
    }

    @Override
    public boolean removeMavenCache() {
        boolean inUse = client.pods().inNamespace(namespace).withLabels(LABELS).list().getItems().stream()
                .anyMatch(pod -> !List.of("Succeeded", "Failed").contains(pod.getStatus().getPhase()));
        if (inUse) return false;
        try {
            // Waits until it is gone; the next prepare creates an empty one.
            client.persistentVolumeClaims().inNamespace(namespace).withName(props.mavenCacheVolume())
                    .withTimeout(1, TimeUnit.MINUTES).delete();
            return true;
        } catch (KubernetesClientTimeoutException e) {
            return false;
        }
    }

    @Override
    public void close() {
        client.close();
    }

    /** Starts the Job and waits for it to end. Returns the container's exit code. */
    private int execute(String name, Container container, UUID huntId, Duration timeout) {
        Job job = new JobBuilder()
                .withNewMetadata().withName(name).withLabels(LABELS).endMetadata()
                .withNewSpec()
                .withBackoffLimit(0) // a failing run is a result to report, not something to retry
                .withActiveDeadlineSeconds(timeout.toSeconds())
                .withTtlSecondsAfterFinished(KEEP_FINISHED_SECONDS)
                .withNewTemplate()
                .withNewMetadata().withLabels(LABELS).endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .withAutomountServiceAccountToken(false) // the tests are untrusted: no Kubernetes API for them
                .withEnableServiceLinks(false)
                .withContainers(container)
                .withVolumes(claimVolume("workspace", workspace(huntId)), claimVolume("m2", props.mavenCacheVolume()))
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
        var jobs = client.batch().v1().jobs().inNamespace(namespace);
        jobs.resource(job).create();
        // shortcut: a quota-full Job waits for room inside its deadline, so a long queue can time runs out;
        // HuntExecution never starts more than the quota holds, so only two hunts at once can hit it.
        Job done;
        try {
            done = jobs.withName(name).waitUntilCondition(KubernetesRunner::finished,
                    timeout.plus(WAIT_MARGIN).toMillis(), TimeUnit.MILLISECONDS);
        } catch (KubernetesClientTimeoutException e) {
            log.warn("[{}] still running after {}; deleting it", name, timeout);
            jobs.withName(name).delete();
            return TIMED_OUT;
        }
        int exit = exitCode(done);
        if (exit != 0) log.info("[{}] exited {}; see kubectl -n {} logs job/{}", name, exit, namespace, name);
        return exit;
    }

    static boolean finished(Job job) {
        return job == null || condition(job, "Complete") != null || condition(job, "Failed") != null;
    }

    private int exitCode(Job job) {
        if (job == null) return 1; // deleted by someone else
        if (condition(job, "Complete") != null) return 0;
        JobCondition failed = condition(job, "Failed");
        if ("DeadlineExceeded".equals(failed.getReason())) return TIMED_OUT;
        // An OOM-killed container reports 137 here, as under Docker.
        List<Pod> pods = client.pods().inNamespace(namespace)
                .withLabel(JOB_NAME_LABEL, job.getMetadata().getName()).list().getItems();
        return pods.stream()
                .flatMap(pod -> pod.getStatus().getContainerStatuses().stream())
                .map(status -> status.getState().getTerminated())
                .filter(Objects::nonNull)
                .map(ContainerStateTerminated::getExitCode)
                .findFirst().orElse(1);
    }

    private static JobCondition condition(Job job, String type) {
        if (job.getStatus() == null) return null;
        return job.getStatus().getConditions().stream()
                .filter(c -> type.equals(c.getType()) && "True".equals(c.getStatus()))
                .findFirst().orElse(null);
    }

    /** Creates the claim unless it exists. The cluster's one node mounts it into every runner pod at once. */
    private void ensureClaim(String name) {
        try {
            client.persistentVolumeClaims().inNamespace(namespace).resource(new PersistentVolumeClaimBuilder()
                    .withNewMetadata().withName(name).withLabels(LABELS).endMetadata()
                    .withNewSpec()
                    .withAccessModes("ReadWriteOnce")
                    .withNewResources().addToRequests("storage", new Quantity("5Gi")).endResources()
                    .endSpec()
                    .build()).create();
        } catch (KubernetesClientException e) {
            if (e.getCode() != 409) throw e; // 409: it already exists
        }
    }

    /**
     * Memory request = limit, and no swap in Kubernetes: a test that leaks memory fails fast instead of crawling.
     * The image runs as uid 10001; these settings stop it from ever gaining more.
     */
    private static Container container(String image, DataSize memory, String cpuLimit, List<EnvVar> env,
            List<VolumeMount> mounts) {
        Quantity mem = new Quantity(memory.toMegabytes() + "Mi");
        var resources = new ContainerBuilder().withNewResources()
                .addToRequests("memory", mem).addToRequests("cpu", new Quantity("100m"))
                .addToLimits("memory", mem);
        if (cpuLimit != null) resources.addToLimits("cpu", new Quantity(cpuLimit));
        return resources.endResources()
                .withName("runner")
                .withImage(image)
                .withImagePullPolicy("Always") // runner tags (jdk17) are reused; Always picks up a rebuilt image
                .withEnv(env)
                .withVolumeMounts(mounts)
                .withNewSecurityContext()
                .withRunAsNonRoot(true)
                .withAllowPrivilegeEscalation(false)
                .withNewCapabilities().withDrop("ALL").endCapabilities()
                .endSecurityContext()
                .build();
    }

    private static VolumeMount mount(String volume, String path, boolean readOnly) {
        return new VolumeMountBuilder().withName(volume).withMountPath(path).withReadOnly(readOnly).build();
    }

    private static Volume claimVolume(String name, String claim) {
        return new VolumeBuilder().withName(name).withNewPersistentVolumeClaim(claim, false).build();
    }

    private static String workspace(UUID huntId) {
        return "hunt-" + huntId;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
