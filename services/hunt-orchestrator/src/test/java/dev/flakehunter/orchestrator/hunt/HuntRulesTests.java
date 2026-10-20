package dev.flakehunter.orchestrator.hunt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

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
