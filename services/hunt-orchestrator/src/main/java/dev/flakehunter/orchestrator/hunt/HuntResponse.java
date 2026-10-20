package dev.flakehunter.orchestrator.hunt;

import java.time.Instant;
import java.util.UUID;

public record HuntResponse(
        UUID id,
        UUID projectId,
        String commitSha,
        HuntStatus status,
        String failureReason,
        Budget budget,
        Integer jdkVersion,
        String runnerImage,
        Instant createdAt,
        Instant updatedAt) {

    static HuntResponse from(Hunt h) {
        return new HuntResponse(h.getId(), h.getProjectId(), h.getCommitSha(), h.getStatus(), h.getFailureReason(),
                h.getBudget(), h.getJdkVersion(), h.getRunnerImage(), h.getCreatedAt(), h.getUpdatedAt());
    }
}
