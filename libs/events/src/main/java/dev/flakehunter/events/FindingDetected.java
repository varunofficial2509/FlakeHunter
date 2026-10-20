package dev.flakehunter.events;

import java.util.List;
import java.util.UUID;

/** Payload of {@code finding.detected}: one test that both passed and failed in a hunt. Key: {@code findingId}. */
public record FindingDetected(
        UUID findingId,
        String testId,
        double score, // 0..1, higher means stronger evidence of flakiness
        int runs, // runs where the test ran (skips excluded)
        int failures, // FAILED or ERROR
        double failureRate,
        double failureRateLow, // 95% Wilson interval: how sure we are about failureRate
        double failureRateHigh,
        List<String> triggeringStrategies, // fail more often than BASELINE; empty if it fails just as often unperturbed
        List<StrategyStats> strategies,
        List<Cluster> failureClusters) { // largest first; one cluster suggests one cause

    public record StrategyStats(String strategy, int runs, int failures) {}

    public record Cluster(String stackHash, int count, String sampleMessage) {}
}
