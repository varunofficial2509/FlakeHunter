package dev.flakehunter.orchestrator.hunt;

import dev.flakehunter.orchestrator.runner.DockerRunner.Machine;
import dev.flakehunter.orchestrator.runner.RunnerSettings.Limits;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The budgets this machine can actually run (tech doc section 5): the UI offers only these, and the API rejects
 * anything else, so a hunt can't ask for more containers than fit in Docker's memory.
 */
record HuntOptions(List<Integer> maxRuns, List<Integer> maxParallelPods, Budget defaults) {

    private static final List<Integer> RUNS = List.of(10, 20, 40, 80);
    private static final List<Integer> PARALLEL = List.of(1, 2, 4, 8);

    static HuntOptions of(Machine machine, Limits runner) {
        long usable = Math.min(runner.totalMemory().toBytes(), machine.usableBytes());
        long fit = Math.max(1, usable / runner.memory().toBytes());
        long cap = Math.min(fit, machine.cpus());
        List<Integer> parallel = PARALLEL.stream().filter(p -> p == 1 || p <= cap).toList();
        int defaultParallel = IntStream.of(4, 2, 1).filter(parallel::contains).findFirst().orElse(1);
        return new HuntOptions(RUNS, parallel, new Budget(40, defaultParallel));
    }

    boolean allows(Budget budget) {
        return maxRuns.contains(budget.maxRuns()) && maxParallelPods.contains(budget.maxParallelPods());
    }
}
