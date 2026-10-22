package dev.flakehunter.events;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Payload of {@code experiment.completed}, and of {@code fix.verified} for a verification: the outcome per
 * requested test. Key: {@code findingId}.
 */
public record ExperimentCompleted(
        UUID findingId,
        UUID experimentId,
        Status status,
        String reason, // why it was REJECTED or FAILED; null when COMPLETED
        int runs, // runs that reported results; crashed or timed-out runs are left out
        List<TestOutcome> tests,
        Map<String, String> sources) { // from sources.exported: repo path → content; null if none arrived

    /** REJECTED: the plan broke a rule and nothing ran. FAILED: preparing the commit (or applying the patch) failed. */
    public enum Status { COMPLETED, REJECTED, FAILED }

    public record TestOutcome(String test, int failures) {}
}
