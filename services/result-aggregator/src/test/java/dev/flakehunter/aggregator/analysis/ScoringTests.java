package dev.flakehunter.aggregator.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flakehunter.events.FindingDetected;
import dev.flakehunter.events.FindingDetected.Cluster;
import dev.flakehunter.events.FindingDetected.StrategyStats;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScoringTests {

    @Test
    void onlyTestsWithBothOutcomesAreFlaky() {
        assertThat(score(List.of(new StrategyStats("BASELINE", 3, 0)), List.of())).isNull(); // always passes
        assertThat(score(List.of(new StrategyStats("BASELINE", 3, 3)), List.of())).isNull(); // broken
    }

    @Test
    void namesTheStrategiesThatFailMoreThanBaseline() {
        FindingDetected f = score(List.of(new StrategyStats("BASELINE", 3, 0), new StrategyStats("TIMEZONE", 10, 4),
                new StrategyStats("SHUFFLE_ORDER", 10, 0)), List.of(new Cluster("abc", 4, "expected 8 but was 7")));

        assertThat(f.runs()).isEqualTo(23);
        assertThat(f.failures()).isEqualTo(4);
        assertThat(f.failureRate()).isEqualTo(0.174);
        assertThat(f.triggeringStrategies()).containsExactly("TIMEZONE");
        // Wilson 95% interval for 4/23 is about 0.07..0.37: few runs, wide interval.
        assertThat(f.failureRateLow()).isBetween(0.06, 0.08);
        assertThat(f.failureRateHigh()).isBetween(0.36, 0.38);
        assertThat(f.score()).isEqualTo(0.8); // 4/5 evidence, one cluster
    }

    @Test
    void moreFailuresAndOneCauseScoreHigher() {
        List<StrategyStats> twoFailures = List.of(new StrategyStats("BASELINE", 10, 2));
        List<StrategyStats> eightFailures = List.of(new StrategyStats("BASELINE", 10, 8));
        double few = score(twoFailures, List.of(new Cluster("a", 2, null))).score();
        double many = score(eightFailures, List.of(new Cluster("a", 8, null))).score();
        double scattered = score(eightFailures, List.of(new Cluster("a", 4, null), new Cluster("b", 4, null))).score();

        assertThat(many).isGreaterThan(few);
        assertThat(many).isGreaterThan(scattered);
        assertThat(score(twoFailures, List.of()).triggeringStrategies()).isEmpty(); // fails without perturbation
    }

    private static FindingDetected score(List<StrategyStats> stats, List<Cluster> clusters) {
        return Scoring.score(UUID.randomUUID(), "com.acme.T#t", stats, clusters).orElse(null);
    }
}
