package dev.flakehunter.project;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        String repoUrl,
        String defaultBranch,
        BuildTool buildTool,
        String testCommand,
        Integer jdkVersion,
        Instant createdAt,
        Instant updatedAt) {

    static ProjectResponse from(Project p) {
        return new ProjectResponse(p.getId(), p.getName(), p.getRepoUrl(), p.getDefaultBranch(), p.getBuildTool(),
                p.getTestCommand(), p.getJdkVersion(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
