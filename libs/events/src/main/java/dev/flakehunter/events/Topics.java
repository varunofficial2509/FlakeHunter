package dev.flakehunter.events;

/** Topic names, matching kafka-init in deploy/compose/compose.yaml. */
public final class Topics {

    public static final String HUNT_STATUS = "hunt.status";
    public static final String TEST_RESULTS = "test.results";
    public static final String RUN_COMPLETED = "run.completed";

    private Topics() {}
}
