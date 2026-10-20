package dev.flakehunter.orchestrator.hunt;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** How much a hunt may spend. Stored as JSONB, so Phase 3 can add maxTokens without a migration. */
public record Budget(
        // At least the 3 baseline runs (tech doc section 9); the cap keeps one hunt from filling a laptop.
        @Min(3) @Max(500) int maxRuns,
        @Min(1) @Max(16) int maxParallelPods) {

    /** Local defaults from tech doc section 9. */
    static final Budget DEFAULT = new Budget(40, 4);
}
