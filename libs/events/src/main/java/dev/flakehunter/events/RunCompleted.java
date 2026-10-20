package dev.flakehunter.events;

import java.util.UUID;

/** Payload of {@code run.completed}: sent once per run, even when the build crashed (ADR 0002). Key: {@code huntId}. */
public record RunCompleted(
        UUID runId,
        String strategy,
        Long seed,
        Status status,
        int exitCode, // of the test command; non-zero is normal when tests fail
        long durationMs,
        int total,
        int passed,
        int failed,
        int errors,
        int skipped) {

    /**
     * SUCCEEDED: tests ran and results were published, even if some failed. FAILED: the build produced no
     * reports, so the run says nothing about flakiness. TIMED_OUT: killed by the run's time limit.
     */
    public enum Status { SUCCEEDED, FAILED, TIMED_OUT }
}
