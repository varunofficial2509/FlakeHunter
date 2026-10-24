package dev.flakehunter.orchestrator.hunt;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** How much a hunt may spend. Stored as JSONB, so a new limit needs no migration. */
public record Budget(
        // At least the 3 baseline runs (tech doc section 9); the cap keeps one hunt from filling a laptop.
        @Min(3) @Max(500) int maxRuns,
        @Min(1) @Max(8) int maxParallelPods) {
}
