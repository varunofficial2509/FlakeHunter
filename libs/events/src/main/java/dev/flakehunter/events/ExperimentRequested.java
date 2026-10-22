package dev.flakehunter.events;

import java.util.List;
import java.util.UUID;

/**
 * Payload of {@code experiment.requested}: an agent asks for targeted runs to test one hypothesis. Key:
 * {@code findingId}. The envelope's huntId is the hunt whose commit is tested. The agent may send the same
 * experimentId more than once; the orchestrator runs it once.
 * Also the payload of {@code verification.requested}: then {@code patch} is the fix (a unified diff, applied
 * with {@code git apply -p1}) and the plan is the condition that made the test fail.
 */
public record ExperimentRequested(UUID findingId, UUID experimentId, String hypothesis, Plan plan, String patch) {

    /**
     * {@code repeat} runs of {@code tests} (Surefire {@code Class} or {@code Class#method}) with one strategy;
     * timezone only for TIMEZONE.
     */
    public record Plan(List<String> tests, String strategy, String timezone, int repeat) {}
}
