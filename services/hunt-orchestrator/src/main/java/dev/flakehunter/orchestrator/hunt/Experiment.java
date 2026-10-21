package dev.flakehunter.orchestrator.hunt;

import dev.flakehunter.events.ExperimentRequested;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Runs an agent asked for to test one hypothesis about a finding (tech doc section 8). */
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

    protected Experiment() {} // for JPA

    Experiment(UUID huntId, ExperimentRequested request, Status status, String reason) {
        this.id = request.experimentId();
        this.findingId = request.findingId();
        this.huntId = huntId;
        this.hypothesis = request.hypothesis();
        this.plan = request.plan();
        this.status = status;
        this.reason = reason;
    }

    void moveTo(Status next, String why) {
        status = next;
        reason = why;
    }

    UUID getId() { return id; }
    UUID getFindingId() { return findingId; }
    UUID getHuntId() { return huntId; }
    ExperimentRequested.Plan getPlan() { return plan; }
    Status getStatus() { return status; }
    String getReason() { return reason; }
}
