package dev.flakehunter.orchestrator.hunt;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One execution of the test suite in one runner container. */
@Entity
@Table(name = "runs")
class Run {

    enum Status {
        SCHEDULED, RUNNING, SUCCEEDED, FAILED, TIMED_OUT;

        boolean isFinished() {
            return this == SUCCEEDED || this == FAILED || this == TIMED_OUT;
        }
    }

    @Id
    private UUID id;

    private UUID huntId;

    @Enumerated(EnumType.STRING)
    private Strategy strategy;

    private Long seed;
    private String timezone;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Instant startedAt;
    private Instant finishedAt;

    // Experiment runs only: which experiment, which tests to run, and which of them failed.
    private UUID experimentId;
    private String testFilter;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> failedTests;

    protected Run() {} // for JPA

    Run(UUID huntId, Strategy strategy, Long seed, String timezone) {
        this.id = UUID.randomUUID();
        this.huntId = huntId;
        this.strategy = strategy;
        this.seed = seed;
        this.timezone = timezone;
        this.status = Status.SCHEDULED;
    }

    Run(UUID huntId, Strategy strategy, Long seed, String timezone, UUID experimentId, String testFilter) {
        this(huntId, strategy, seed, timezone);
        this.experimentId = experimentId;
        this.testFilter = testFilter;
    }

    void start() {
        status = Status.RUNNING;
        startedAt = Instant.now();
    }

    void finish(Status result, List<String> failed) {
        status = result;
        failedTests = failed;
        finishedAt = Instant.now();
    }

    UUID getId() { return id; }
    UUID getHuntId() { return huntId; }
    Strategy getStrategy() { return strategy; }
    Long getSeed() { return seed; }
    String getTimezone() { return timezone; }
    Status getStatus() { return status; }
    UUID getExperimentId() { return experimentId; }
    String getTestFilter() { return testFilter; }
    List<String> getFailedTests() { return failedTests; }
}
