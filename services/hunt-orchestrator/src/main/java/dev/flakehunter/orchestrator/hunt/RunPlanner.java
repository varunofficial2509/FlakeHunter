package dev.flakehunter.orchestrator.hunt;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Turns a budget into runs (tech doc section 9, "Default hunt plan"). */
final class RunPlanner {

    static final int BASELINE_RUNS = 3;

    // shortcut: RANDOM_SEED and ISOLATED are not wired in the runner yet, so they are not planned.
    private static final List<Strategy> PERTURBED =
            List.of(Strategy.SHUFFLE_ORDER, Strategy.TIMEZONE, Strategy.PARALLEL, Strategy.CPU_THROTTLE);
    // Far-apart offsets, including one where the date differs from UTC for most of the day.
    private static final List<String> TIMEZONES =
            List.of("UTC", "Asia/Kolkata", "America/Los_Angeles", "Pacific/Kiritimati");

    private RunPlanner() {}

    /**
     * Baseline runs first, then the perturbed strategies in turn.
     * shortcut: all runs are planned up front; stopping when the baseline fails every time needs results from
     * result-aggregator, which comes next.
     */
    static List<Run> plan(UUID huntId, Budget budget, RandomGenerator random) {
        List<Run> runs = new ArrayList<>();
        for (int i = 0; i < BASELINE_RUNS; i++) {
            runs.add(new Run(huntId, Strategy.BASELINE, null, null));
        }
        for (int i = 0; i < budget.maxRuns() - BASELINE_RUNS; i++) {
            Strategy strategy = PERTURBED.get(i % PERTURBED.size());
            // Seed recorded on every shuffled run, so a failing order can be replayed exactly.
            Long seed = strategy == Strategy.SHUFFLE_ORDER ? random.nextLong(1, 1_000_000) : null;
            String timezone = strategy == Strategy.TIMEZONE
                    ? TIMEZONES.get((i / PERTURBED.size()) % TIMEZONES.size()) : null;
            runs.add(new Run(huntId, strategy, seed, timezone));
        }
        return runs;
    }
}
