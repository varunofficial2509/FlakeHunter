package dev.flakehunter.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "projects")
public class Project {

    @Id
    private UUID id;

    private String name;

    // Changing the repository means a different project, so there is no setter.
    @Column(updatable = false)
    private String repoUrl;

    private String defaultBranch;

    @Enumerated(EnumType.STRING)
    private BuildTool buildTool;

    private String testCommand;
    private Integer jdkVersion;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    protected Project() {} // for JPA

    public Project(String name, String repoUrl, String defaultBranch, BuildTool buildTool,
            String testCommand, Integer jdkVersion) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.repoUrl = repoUrl;
        this.defaultBranch = defaultBranch;
        this.buildTool = buildTool;
        this.testCommand = testCommand;
        this.jdkVersion = jdkVersion;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Applies the non-null fields of a partial update. */
    void update(UpdateProjectRequest r) {
        if (r.name() != null) name = r.name();
        if (r.defaultBranch() != null) defaultBranch = r.defaultBranch();
        if (r.buildTool() != null) buildTool = r.buildTool();
        if (r.testCommand() != null) testCommand = r.testCommand();
        if (r.jdkVersion() != null) jdkVersion = r.jdkVersion();
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getRepoUrl() { return repoUrl; }
    public String getDefaultBranch() { return defaultBranch; }
    public BuildTool getBuildTool() { return buildTool; }
    public String getTestCommand() { return testCommand; }
    public Integer getJdkVersion() { return jdkVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
