package dev.flakehunter.orchestrator.hunt;

import dev.flakehunter.orchestrator.project.ProjectClient;
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

    // Copied from the project at start (see V2 migration).
    @Column(updatable = false)
    private String repoUrl;

    @Column(updatable = false)
    private String buildTool;

    @Column(updatable = false)
    private Integer jdkVersion;

    @Column(updatable = false)
    private String mavenArgs;

    private String failureReason;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    // Catches a concurrent update that slipped past the row lock in HuntRepository.lockById.
    @Version
    private int version;

    protected Hunt() {} // for JPA

    Hunt(UUID projectId, String commitSha, Budget budget, ProjectClient.Project project) {
        this.id = UUID.randomUUID();
        this.projectId = projectId;
        this.commitSha = commitSha;
        this.status = HuntStatus.CREATED;
        this.budget = budget;
        this.repoUrl = project.repoUrl();
        this.buildTool = project.buildTool();
        this.jdkVersion = project.jdkVersion();
        // testCommand holds extra Maven arguments only, never a shell command; Maven reads them from MAVEN_ARGS.
        this.mavenArgs = project.testCommand();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Moves to the next status, refusing moves the lifecycle doesn't allow. */
    void moveTo(HuntStatus next) {
        if (!status.canMoveTo(next)) {
            throw new IllegalStateException("Hunt " + id + " cannot move from " + status + " to " + next);
        }
        status = next;
        updatedAt = Instant.now();
    }

    void fail(String reason) {
        moveTo(HuntStatus.FAILED);
        failureReason = reason;
    }

    void useImage(String image) {
        runnerImage = image;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public String getCommitSha() { return commitSha; }
    public HuntStatus getStatus() { return status; }
    public Budget getBudget() { return budget; }
    public String getRunnerImage() { return runnerImage; }
    public String getRepoUrl() { return repoUrl; }
    public String getBuildTool() { return buildTool; }
    public Integer getJdkVersion() { return jdkVersion; }
    public String getMavenArgs() { return mavenArgs; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
