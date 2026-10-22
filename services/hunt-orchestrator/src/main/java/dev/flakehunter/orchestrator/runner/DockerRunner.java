package dev.flakehunter.orchestrator.runner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/** Starts runner containers with the docker CLI (Docker Compose). In the cluster, KubernetesRunner does this. */
@Component
@ConditionalOnProperty(name = "flakehunter.runner.platform", havingValue = "docker", matchIfMissing = true)
public class DockerRunner implements Runner {

    private static final Logger log = LoggerFactory.getLogger(DockerRunner.class);

    private final RunnerProperties props;

    DockerRunner(RunnerProperties props) {
        this.props = props;
    }

    @Override
    public String image(int jdk) {
        return props.imagePrefix() + jdk;
    }

    @Override
    public Machine machine() {
        try {
            Process process = new ProcessBuilder("docker", "info", "--format", "{{.MemTotal}} {{.NCPU}}")
                    .redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw unavailable("docker info failed: " + out, null);
            }
            String[] parts = out.split(" ");
            return new Machine(Long.parseLong(parts[0]), Integer.parseInt(parts[1]), props.infraMemory().toBytes());
        } catch (IOException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
            throw unavailable("Could not read docker info; is Docker running?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("Interrupted while reading docker info", e);
        }
    }

    @Override
    public int prepare(PrepareJob job) {
        String name = "flakehunter-prepare-" + job.huntId();
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "--rm", "--name", name,
                "--network", props.network(),
                "--memory", memoryLimit(job.memory()), "--memory-swap", memoryLimit(job.memory()),
                "-v", volume(job.huntId()) + ":/workspace",
                "-v", props.mavenCacheVolume() + ":/home/runner/.m2",
                "-e", "REPO_URL=" + job.repoUrl(),
                "-e", "COMMIT_SHA=" + job.commitSha(),
                "-e", "MAVEN_ARGS=" + nullToEmpty(job.mavenArgs()),
                "--entrypoint", "/opt/flakehunter/prepare.sh"));
        if (job.exportTests() != null) {
            cmd.addAll(List.of("-e", "EXPORT_TESTS=" + job.exportTests(), "-e", "HUNT_ID=" + job.huntId(),
                    "-e", "KAFKA_BOOTSTRAP_SERVERS=" + props.kafkaBootstrapServers()));
        }
        if (job.patch() != null) cmd.addAll(List.of("-e", "PATCH=" + job.patch()));
        cmd.add(image(job.jdk()));
        return start(name, cmd, props.prepareTimeout());
    }

    @Override
    public int run(RunJob job) {
        String name = "flakehunter-run-" + job.runId();
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "--rm", "--name", name,
                "--network", props.network(),
                "--memory", memoryLimit(job.memory()), "--memory-swap", memoryLimit(job.memory()),
                // Read-only: each run copies the workspace, so runs can't change each other's files.
                "-v", volume(job.huntId()) + ":/prepared:ro",
                "-v", props.mavenCacheVolume() + ":/home/runner/.m2",
                "-e", "HUNT_ID=" + job.huntId(),
                "-e", "RUN_ID=" + job.runId(),
                "-e", "STRATEGY=" + job.strategy(),
                "-e", "MAVEN_ARGS=" + nullToEmpty(job.mavenArgs()),
                "-e", "KAFKA_BOOTSTRAP_SERVERS=" + props.kafkaBootstrapServers(),
                "-e", "RUN_TIMEOUT_SECONDS=" + props.runTimeout().toSeconds()));
        if (job.seed() != null) cmd.addAll(List.of("-e", "SEED=" + job.seed()));
        if (job.timezone() != null) cmd.addAll(List.of("-e", "TZ=" + job.timezone()));
        if (job.testFilter() != null) cmd.addAll(List.of("-e", "TEST_FILTER=" + job.testFilter()));
        // Starved CPU exposes tests that assume work finishes within a fixed time (tech doc section 9).
        if (job.strategy().equals("CPU_THROTTLE")) cmd.addAll(List.of("--cpus", "0.25"));
        cmd.add(image(job.jdk()));
        return start(name, cmd, props.runTimeout().plus(PUBLISH_MARGIN));
    }

    @Override
    public void removeVolume(UUID huntId) {
        String name = "remove-" + volume(huntId);
        exec(name, List.of("docker", "volume", "rm", "--force", volume(huntId)), Duration.ofMinutes(1));
    }

    @Override
    public boolean removeMavenCache() {
        return exec("remove-maven-cache", List.of("docker", "volume", "rm", "--force", props.mavenCacheVolume()),
                Duration.ofMinutes(1)) == 0;
    }

    private static RunnerUnavailableException unavailable(String message, Throwable cause) {
        return new RunnerUnavailableException("Docker is not running; start it", message, cause);
    }

    /** Memory and swap set to the same value: no swap, so a test that leaks memory fails fast instead of crawling. */
    private static String memoryLimit(DataSize memory) {
        return memory.toMegabytes() + "m";
    }

    private static String volume(UUID huntId) {
        return "flakehunter-hunt-" + huntId;
    }

    /**
     * Starts the container, or, when one with this name still runs from before an orchestrator restart, attaches
     * to it instead: docker attach exits with the container's exit code, as docker run does.
     * shortcut: a container that exits between inspect and attach is reported as failed; rare, costs one run.
     */
    private int start(String name, List<String> cmd, Duration timeout) {
        if (exec(name, List.of("docker", "container", "inspect", "--format", "{{.State.Status}}", name),
                Duration.ofSeconds(30)) != 0) {
            return exec(name, cmd, timeout);
        }
        log.info("[{}] already exists; attaching to it again", name);
        return exec(name, List.of("docker", "attach", "--no-stdin", name), timeout);
    }

    // Arguments go to docker as a list, never through a shell, so no value can inject a command.
    private int exec(String name, List<String> cmd, Duration timeout) {
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            Thread.ofVirtual().start(() -> logOutput(name, process));
            if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) return process.exitValue();
            log.warn("[{}] still running after {}; killing it", name, timeout);
            new ProcessBuilder("docker", "rm", "--force", name).start().waitFor(30, TimeUnit.SECONDS);
            process.destroyForcibly();
            return TIMED_OUT;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not run docker; is it installed and on PATH?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + name, e);
        }
    }

    private static void logOutput(String name, Process process) {
        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            out.lines().forEach(line -> log.info("[{}] {}", name, line));
        } catch (IOException e) {
            log.debug("[{}] output closed", name, e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
