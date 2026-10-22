package dev.flakehunter.orchestrator.hunt;

import java.util.UUID;

/**
 * How far one experiment or verification got, for the UI's "8/100 runs" line. failing counts runs where a
 * planned test failed; crashed counts runs that ended without results (build crash, timeout, OOM).
 */
public record ExperimentProgress(
        UUID id, UUID findingId, boolean verification, String status, int planned, int finished, int failing,
        int crashed) {}
