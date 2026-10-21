package dev.flakehunter.orchestrator.runner;

import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.util.unit.DataSize;
import org.springframework.web.ErrorResponseException;

/**
 * Starts prepare and run containers. {@code flakehunter.runner.platform} picks one: DockerRunner under Compose
 * (the default), KubernetesRunner in the cluster.
 */
public interface Runner {

    record PrepareJob(UUID huntId, int jdk, String repoUrl, String commitSha, String mavenArgs, DataSize memory) {}

    /** huntId names the prepared workspace: the hunt's own, or an experiment's. testFilter is Surefire -Dtest. */
    record RunJob(UUID huntId, UUID runId, int jdk, String strategy, Long seed, String timezone, String testFilter,
            String mavenArgs, DataSize memory) {}

    /** Where runners live: Docker's VM, or the cluster's runner quota. Its memory, not the Mac's, is the limit. */
    record Machine(long memoryBytes, int cpus, long infraBytes) {

        /** What the runners may use in total: under Compose, Kafka and Postgres share Docker's VM with them. */
        public long usableBytes() {
            return memoryBytes - infraBytes;
        }
    }

    /** Answered as a 503 problem detail by any controller. */
    class RunnerUnavailableException extends ErrorResponseException {
        public RunnerUnavailableException(String detail, String message, Throwable cause) {
            super(HttpStatus.SERVICE_UNAVAILABLE, ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    detail), new IllegalStateException(message, cause));
        }
    }

    /** Exit code we report when we had to kill a container; the same one {@code timeout} uses. */
    int TIMED_OUT = 124;
    /** 128 + SIGKILL: what a container exits with when it hits its memory limit. */
    int KILLED = 137;
    /** The run script stops the tests at run-timeout; this margin lets it still publish before we kill it. */
    Duration PUBLISH_MARGIN = Duration.ofMinutes(2);

    String image(int jdk);

    Machine machine();

    /** Clones the commit and compiles the tests into the hunt's volume. Returns the container's exit code. */
    int prepare(PrepareJob job);

    /** Runs the tests once and publishes the results to Kafka. Returns the container's exit code. */
    int run(RunJob job);

    /** Deletes the hunt's prepared workspace once no container uses it any more. */
    void removeVolume(UUID huntId);

    /** Empties the shared Maven repository. False if a container still uses it. */
    boolean removeMavenCache();
}
