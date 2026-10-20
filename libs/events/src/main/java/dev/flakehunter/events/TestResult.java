package dev.flakehunter.events;

import java.util.UUID;

/** Payload of {@code test.results}: one test's outcome in one run. Key: {@code huntId:testId}. */
public record TestResult(
        UUID runId,
        String testId, // "class#method"
        String strategy,
        Long seed,
        Status status,
        long durationMs,
        String failureMessage, // null unless FAILED or ERROR
        String stackHash, // groups failures with the same cause; null unless FAILED or ERROR
        int orderIndex) { // position of the test in this run

    public enum Status { PASSED, FAILED, ERROR, SKIPPED }
}
