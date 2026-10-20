package dev.flakehunter.orchestrator.hunt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "hunts")
public class Hunt {

    @Id
    private UUID id;

    @Column(updatable = false)
    private UUID projectId;

    @Column(updatable = false)
    private String commitSha;

    @Enumerated(EnumType.STRING)
    private HuntStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    private Budget budget;

    private String runnerImage;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    // Two listeners moving the same hunt at once: the second update fails instead of overwriting the first.
    @Version
    private int version;

    protected Hunt() {} // for JPA

    Hunt(UUID projectId, String commitSha, Budget budget) {
        this.id = UUID.randomUUID();
        this.projectId = projectId;
        this.commitSha = commitSha;
        this.status = HuntStatus.CREATED;
        this.budget = budget;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public String getCommitSha() { return commitSha; }
    public HuntStatus getStatus() { return status; }
    public Budget getBudget() { return budget; }
    public String getRunnerImage() { return runnerImage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
