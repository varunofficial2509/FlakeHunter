package dev.flakehunter.orchestrator.hunt;

import java.util.Set;

/** Hunt lifecycle from tech doc section 3. */
public enum HuntStatus {
    CREATED,
    PREPARING,
    RUNNING,
    ANALYZING,
    DIAGNOSING,
    VERIFYING,
    COMPLETED,
    AWAITING_AI,
    FAILED,
    CANCELLED;

    /** The saga's rules: a late or duplicate event must never move a hunt backwards. */
    boolean canMoveTo(HuntStatus next) {
        return switch (this) {
            case CREATED -> Set.of(PREPARING, FAILED, CANCELLED).contains(next);
            case PREPARING -> Set.of(RUNNING, FAILED, CANCELLED).contains(next);
            case RUNNING -> Set.of(ANALYZING, FAILED, CANCELLED).contains(next);
            case ANALYZING -> Set.of(COMPLETED, DIAGNOSING, AWAITING_AI, FAILED).contains(next);
            case AWAITING_AI -> Set.of(DIAGNOSING, CANCELLED).contains(next);
            case DIAGNOSING -> Set.of(VERIFYING, COMPLETED, FAILED).contains(next);
            case VERIFYING -> Set.of(COMPLETED, FAILED).contains(next);
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }
}
