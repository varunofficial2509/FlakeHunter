package dev.flakehunter.orchestrator.hunt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flakehunter.orchestrator.runner.Runner.Machine;
import dev.flakehunter.orchestrator.runner.RunnerSettings.Limits;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** The planner and the lifecycle rules, without Spring. */
class HuntRulesTests {

    @Test
    void plansBaselineFirstThenEachStrategyInTurn() {
        List<Run> runs = RunPlanner.plan(UUID.randomUUID(), new Budget(11, 4), RandomGenerator.getDefault());

        assertThat(runs).extracting(Run::getStrategy).containsExactly(
                Strategy.BASELINE, Strategy.BASELINE, Strategy.BASELINE,
                Strategy.SHUFFLE_ORDER, Strategy.TIMEZONE, Strategy.PARALLEL, Strategy.CPU_THROTTLE,
                Strategy.SHUFFLE_ORDER, Strategy.TIMEZONE, Strategy.PARALLEL, Strategy.CPU_THROTTLE);
        assertThat(runs).filteredOn(r -> r.getStrategy() == Strategy.SHUFFLE_ORDER)
                .allMatch(r -> r.getSeed() != null);
        assertThat(runs).filteredOn(r -> r.getStrategy() == Strategy.TIMEZONE).extracting(Run::getTimezone)
                .containsExactly("UTC", "Asia/Kolkata");
        assertThat(runs).filteredOn(r -> r.getStrategy() != Strategy.SHUFFLE_ORDER)
                .allMatch(r -> r.getSeed() == null);
    }

    @Test
    void offersOnlyBudgetsThatFitInDockersMemoryAndCpus() {
        Limits runner = new Limits(DataSize.ofMegabytes(1536), DataSize.ofGigabytes(6));

        // This laptop: 7.8 GB VM, 10 CPUs. 6 GB of runners fit, so 4 at once.
        HuntOptions laptop = HuntOptions.of(new Machine(8_392_605_696L, 10, 1L << 30), runner);
        assertThat(laptop.maxParallelPods()).containsExactly(1, 2, 4);
        assertThat(laptop.defaults()).isEqualTo(new Budget(40, 4));
        assertThat(laptop.allows(new Budget(40, 8))).isFalse();
        assertThat(laptop.allows(new Budget(15, 2))).isFalse(); // only the listed run counts

        // A small VM: 4 GB minus 1 GB for Kafka and Postgres leaves room for 2 runners.
        HuntOptions small = HuntOptions.of(new Machine(4L << 30, 8, 1L << 30), runner);
        assertThat(small.maxParallelPods()).containsExactly(1, 2);
        assertThat(small.defaults()).isEqualTo(new Budget(40, 2));

        // Too little memory for even one: still offer 1, Docker's limit then decides.
        assertThat(HuntOptions.of(new Machine(1L << 30, 1, 1L << 30), runner).maxParallelPods()).containsExactly(1);
    }

    @Test
    void lifecycleNeverMovesBackwardsOrLeavesAnEndState() {
        assertThat(HuntStatus.CREATED.canMoveTo(HuntStatus.PREPARING)).isTrue();
        assertThat(HuntStatus.RUNNING.canMoveTo(HuntStatus.ANALYZING)).isTrue();
        assertThat(HuntStatus.ANALYZING.canMoveTo(HuntStatus.RUNNING)).isFalse();
        assertThat(HuntStatus.RUNNING.canMoveTo(HuntStatus.COMPLETED)).isFalse(); // must be analyzed first
        for (HuntStatus next : HuntStatus.values()) {
            assertThat(HuntStatus.FAILED.canMoveTo(next)).isFalse();
            assertThat(HuntStatus.COMPLETED.canMoveTo(next)).isFalse();
        }
    }
}
