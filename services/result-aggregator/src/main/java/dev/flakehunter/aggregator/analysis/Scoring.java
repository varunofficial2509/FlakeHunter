package dev.flakehunter.aggregator.analysis;

import dev.flakehunter.events.FindingDetected;
import dev.flakehunter.events.FindingDetected.Cluster;
import dev.flakehunter.events.FindingDetected.StrategyStats;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tech doc section 9: decides whether one test is flaky and how strong the evidence is. No I/O, so easy to test. */
final class Scoring {

    private static final double Z = 1.96; // 95%

    private Scoring() {}

    /** Empty unless the test both passed and failed: always failing is broken, not flaky. */
    static Optional<FindingDetected> score(UUID findingId, String testId, List<StrategyStats> strategies,
            List<Cluster> clusters) {
        int n = strategies.stream().mapToInt(StrategyStats::runs).sum();
        int f = strategies.stream().mapToInt(StrategyStats::failures).sum();
        if (f == 0 || f == n) return Optional.empty();

        double baseline = strategies.stream().filter(s -> s.strategy().equals("BASELINE")).findFirst()
                .map(Scoring::rate).orElse(0.0);
        List<String> triggering = strategies.stream()
                .filter(s -> !s.strategy().equals("BASELINE") && rate(s) > baseline)
                .map(StrategyStats::strategy).sorted().toList();
        List<Cluster> sorted = clusters.stream().sorted(Comparator.comparingInt(Cluster::count).reversed()).toList();

        // shortcut: hand-picked weights, tune against benchmark/ once there are enough known flaky tests.
        double evidence = f / (f + 1.0); // 1 failure 0.5, 3 failures 0.75, 9 failures 0.9
        double consistency = sorted.isEmpty() ? 1 : (double) sorted.getFirst().count() / f; // one cause, one cluster
        double score = evidence * (0.5 + 0.5 * consistency);

        double p = (double) f / n;
        double center = (p + Z * Z / (2 * n)) / (1 + Z * Z / n);
        double half = Z * Math.sqrt(p * (1 - p) / n + Z * Z / (4.0 * n * n)) / (1 + Z * Z / n);
        return Optional.of(new FindingDetected(findingId, testId, round(score), n, f, round(p),
                round(Math.max(0, center - half)), round(Math.min(1, center + half)), triggering,
                strategies.stream().sorted(Comparator.comparing(StrategyStats::strategy)).toList(), sorted));
    }

    private static double rate(StrategyStats s) {
        return s.runs() == 0 ? 0 : (double) s.failures() / s.runs();
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
