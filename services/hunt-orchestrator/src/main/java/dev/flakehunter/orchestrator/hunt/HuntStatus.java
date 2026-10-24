package dev.flakehunter.orchestrator.hunt;

import java.util.Set;

/** Hunt lifecycle from tech doc section 3. */
public enum HuntStatus {
    CREATED,
    PREPARING,
    RUNNING,
    ANALYZING,
    DIAGNOSING,
    COMPLETED,
    AWAITING_AI,
    FAILED;

    /** The saga's rules: a late or duplicate event must never move a hunt backwards. */
    boolean canMoveTo(HuntStatus next) {
        return switch (this) {
            case CREATED -> Set.of(PREPARING, FAILED).contains(next);
            case PREPARING -> Set.of(RUNNING, FAILED).contains(next);
            case RUNNING -> Set.of(ANALYZING, FAILED).contains(next);
            case ANALYZING -> Set.of(COMPLETED, DIAGNOSING, AWAITING_AI, FAILED).contains(next);
            case AWAITING_AI -> next == DIAGNOSING;
            case DIAGNOSING -> Set.of(COMPLETED, FAILED).contains(next);
            case COMPLETED, FAILED -> false;
        };
    }
}
