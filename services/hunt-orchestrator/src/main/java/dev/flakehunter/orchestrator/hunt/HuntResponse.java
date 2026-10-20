package dev.flakehunter.orchestrator.hunt;

import java.time.Instant;
import java.util.UUID;

public record HuntResponse(
        UUID id,
        UUID projectId,
        String commitSha,
        HuntStatus status,
        Budget budget,
        String runnerImage,
        Instant createdAt,
        Instant updatedAt) {

    static HuntResponse from(Hunt h) {
        return new HuntResponse(h.getId(), h.getProjectId(), h.getCommitSha(), h.getStatus(), h.getBudget(),
                h.getRunnerImage(), h.getCreatedAt(), h.getUpdatedAt());
    }
}
