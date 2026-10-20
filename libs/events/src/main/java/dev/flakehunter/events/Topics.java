package dev.flakehunter.events;

/** Topic names, matching kafka-init in deploy/compose/compose.yaml. */
public final class Topics {

    public static final String HUNT_STATUS = "hunt.status";
    public static final String TEST_RESULTS = "test.results";
    public static final String RUN_COMPLETED = "run.completed";
    public static final String FINDING_DETECTED = "finding.detected";
    public static final String ANALYSIS_COMPLETED = "analysis.completed";

    private Topics() {}
}
