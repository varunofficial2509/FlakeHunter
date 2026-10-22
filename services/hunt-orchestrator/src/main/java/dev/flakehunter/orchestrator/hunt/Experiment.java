package dev.flakehunter.orchestrator.hunt;

import dev.flakehunter.events.ExperimentRequested;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Runs an agent asked for to test one hypothesis about a finding (tech doc section 8), or, with a patch, to
 * verify a fix.
 */
@Entity
@Table(name = "experiments")
class Experiment {

    enum Status { PREPARING, RUNNING, COMPLETED, REJECTED, FAILED }

    @Id
    private UUID id;

    private UUID findingId;
    private UUID huntId;
    private String hypothesis;

    @JdbcTypeCode(SqlTypes.JSON)
    private ExperimentRequested.Plan plan;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String reason;
    private String patch;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> sources;

    protected Experiment() {} // for JPA

    Experiment(UUID huntId, ExperimentRequested request, Status status, String reason) {
        this.id = request.experimentId();
        this.findingId = request.findingId();
        this.huntId = huntId;
        this.hypothesis = request.hypothesis();
        this.plan = request.plan();
        this.patch = request.patch();
        this.status = status;
        this.reason = reason;
    }

    void moveTo(Status next, String why) {
        status = next;
        reason = why;
    }

    /** The first export wins: a retried prepare sends the same files again. */
    void exported(Map<String, String> files) {
        if (sources == null) sources = files;
    }

    boolean isVerification() { return patch != null; }

    UUID getId() { return id; }
    UUID getFindingId() { return findingId; }
    UUID getHuntId() { return huntId; }
    ExperimentRequested.Plan getPlan() { return plan; }
    Status getStatus() { return status; }
    String getReason() { return reason; }
    String getPatch() { return patch; }
    Map<String, String> getSources() { return sources; }
}
