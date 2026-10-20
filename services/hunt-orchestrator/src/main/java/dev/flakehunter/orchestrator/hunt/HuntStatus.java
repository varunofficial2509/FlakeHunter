package dev.flakehunter.orchestrator.hunt;

/** Hunt lifecycle from tech doc section 3. Only CREATED is used until the saga is built. */
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
    CANCELLED
}
