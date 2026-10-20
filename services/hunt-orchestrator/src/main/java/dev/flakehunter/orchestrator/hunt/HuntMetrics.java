package dev.flakehunter.orchestrator.hunt;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Hunt gauges for the Grafana platform dashboard. Read from the database at each scrape, so they stay right
 * across restarts and whichever replica answers.
 */
@Component
class HuntMetrics {

    HuntMetrics(MeterRegistry registry, HuntRepository hunts, RunRepository runs) {
        for (HuntStatus status : HuntStatus.values()) {
            Gauge.builder("flakehunter.hunts", hunts, r -> r.countByStatus(status))
                    .description("Hunts in each status").tag("status", status.name()).register(registry);
        }
        Gauge.builder("flakehunter.runs.active", runs, r -> r.countByStatus(Run.Status.RUNNING))
                .description("Runner containers started and not yet reported").register(registry);
    }
}
