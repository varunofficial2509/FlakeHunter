package dev.flakehunter.orchestrator.hunt;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

// shortcut: projectId is not checked against project-service yet; add the lookup when the prepare step needs the repo URL.
public record CreateHuntRequest(
        @NotNull UUID projectId,
        // The SHA is later passed to git, so accept only a full lowercase hex SHA.
        // shortcut: required for now; resolve the branch HEAD when commitSha is missing once cloning exists.
        @NotNull @Pattern(regexp = "^[0-9a-f]{40}$", message = "must be a full 40-character commit SHA")
                String commitSha,
        @Valid Budget budget) {

    Hunt toHunt() {
        return new Hunt(projectId, commitSha, budget == null ? Budget.DEFAULT : budget);
    }
}
