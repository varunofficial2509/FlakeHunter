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
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.ErrorResponseException;

/**
 * Starts runner containers with the docker CLI (Phase 1, Docker Compose).
 * shortcut: Docker only; Phase 2 replaces this class with Kubernetes Jobs created through fabric8.
 */
@Component
public class DockerRunner {

    public record PrepareJob(UUID huntId, int jdk, String repoUrl, String commitSha, String mavenArgs,
            DataSize memory) {}

    /** What Docker's VM offers: runners live inside it, so its memory, not the Mac's, is the limit. */
    public record Machine(long memoryBytes, int cpus, long infraBytes) {

        /** What the runners may use in total: Kafka and Postgres share Docker's VM with them. */
        public long usableBytes() {
            return memoryBytes - infraBytes;
        }
    }

    /** Answered as a 503 problem detail by any controller. */
    public static class DockerUnavailableException extends ErrorResponseException {
        DockerUnavailableException(String message, Throwable cause) {
            super(HttpStatus.SERVICE_UNAVAILABLE, ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "Docker is not running; start it"), new IllegalStateException(message, cause));
        }
    }

    public record RunJob(UUID huntId, UUID runId, int jdk, String strategy, Long seed, String timezone,
            String mavenArgs, DataSize memory) {}

    /** Exit code we report when we had to kill a container; the same one {@code timeout} uses. */
    public static final int TIMED_OUT = 124;
    /** 128 + SIGKILL: what a container exits with when it hits its memory limit. */
    public static final int KILLED = 137;

    private static final Logger log = LoggerFactory.getLogger(DockerRunner.class);
    // The run script stops the tests at run-timeout; this margin lets it still publish before we kill it.
    private static final Duration PUBLISH_MARGIN = Duration.ofMinutes(2);

    private final RunnerProperties props;

    DockerRunner(RunnerProperties props) {
        this.props = props;
    }

    public String image(int jdk) {
        return props.imagePrefix() + jdk;
    }

    public Machine machine() {
        try {
            Process process = new ProcessBuilder("docker", "info", "--format", "{{.MemTotal}} {{.NCPU}}")
                    .redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new DockerUnavailableException("docker info failed: " + out, null);
            }
            String[] parts = out.split(" ");
            return new Machine(Long.parseLong(parts[0]), Integer.parseInt(parts[1]), props.infraMemory().toBytes());
        } catch (IOException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
            throw new DockerUnavailableException("Could not read docker info; is Docker running?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DockerUnavailableException("Interrupted while reading docker info", e);
        }
    }

    /** Clones the commit and compiles the tests into the hunt's volume. Returns the container's exit code. */
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
                "--entrypoint", "/opt/flakehunter/prepare.sh",
                image(job.jdk())));
        return exec(name, cmd, props.prepareTimeout());
    }

    /** Runs the tests once and publishes the results to Kafka. Returns the container's exit code. */
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
        // Starved CPU exposes tests that assume work finishes within a fixed time (tech doc section 9).
        if (job.strategy().equals("CPU_THROTTLE")) cmd.addAll(List.of("--cpus", "0.25"));
        cmd.add(image(job.jdk()));
        return exec(name, cmd, props.runTimeout().plus(PUBLISH_MARGIN));
    }

    /** Deletes the hunt's prepared workspace once no container uses it any more. */
    public void removeVolume(UUID huntId) {
        String name = "remove-" + volume(huntId);
        exec(name, List.of("docker", "volume", "rm", "--force", volume(huntId)), Duration.ofMinutes(1));
    }

    /** Empties the shared Maven repository. False if a container still uses it. */
    public boolean removeMavenCache() {
        return exec("remove-maven-cache", List.of("docker", "volume", "rm", "--force", props.mavenCacheVolume()),
                Duration.ofMinutes(1)) == 0;
    }

    /** Memory and swap set to the same value: no swap, so a test that leaks memory fails fast instead of crawling. */
    private static String memoryLimit(DataSize memory) {
        return memory.toMegabytes() + "m";
    }

    private static String volume(UUID huntId) {
        return "flakehunter-hunt-" + huntId;
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
