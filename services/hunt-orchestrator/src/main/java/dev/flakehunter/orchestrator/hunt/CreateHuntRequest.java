package dev.flakehunter.orchestrator.hunt;

import dev.flakehunter.orchestrator.project.ProjectClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record CreateHuntRequest(
        @NotNull UUID projectId,
        // The SHA is later passed to git, so accept only a full lowercase hex SHA.
        // shortcut: required; resolve the branch HEAD when it is missing if hunts start from a branch name.
        @NotNull @Pattern(regexp = "^[0-9a-f]{40}$", message = "must be a full 40-character commit SHA")
                String commitSha,
        @Valid Budget budget) {

    Hunt toHunt(ProjectClient.Project project, Budget chosen) {
        return new Hunt(projectId, commitSha, chosen, project);
    }
}
