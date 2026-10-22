package dev.flakehunter.events;

/** Topic names, matching kafka-init in deploy/compose/compose.yaml. */
public final class Topics {

    public static final String HUNT_STATUS = "hunt.status";
    public static final String TEST_RESULTS = "test.results";
    public static final String RUN_COMPLETED = "run.completed";
    public static final String FINDING_DETECTED = "finding.detected";
    public static final String ANALYSIS_COMPLETED = "analysis.completed";
    public static final String EXPERIMENT_REQUESTED = "experiment.requested";
    public static final String EXPERIMENT_COMPLETED = "experiment.completed";
    public static final String SOURCES_EXPORTED = "sources.exported";
    public static final String VERIFICATION_REQUESTED = "verification.requested";
    public static final String FIX_VERIFIED = "fix.verified";
    public static final String DIAGNOSIS_COMPLETED = "diagnosis.completed";
    public static final String DIAGNOSIS_UPDATED = "diagnosis.updated";

    /** Appended to a consumed topic's name for its dead-letter topic, e.g. {@code run.completed.dlq}. */
    public static final String DLQ_SUFFIX = ".dlq";

    private Topics() {}
}
