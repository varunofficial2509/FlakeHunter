package dev.flakehunter.orchestrator.hunt;

import dev.flakehunter.orchestrator.outbox.Outbox;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Hunt state changes. Each one saves the hunt and its hunt.status event in one transaction. */
@Service
class HuntService {

    static final String HUNT_STATUS = "hunt.status";

    private final HuntRepository hunts;
    private final Outbox outbox;

    HuntService(HuntRepository hunts, Outbox outbox) {
        this.hunts = hunts;
        this.outbox = outbox;
    }

    // Called by the REST API now, and by the hunt.requested listener later.
    @Transactional
    Hunt start(CreateHuntRequest request) {
        Hunt hunt = hunts.save(request.toHunt());
        outbox.add(HUNT_STATUS, hunt.getId(), Map.of(
                "status", hunt.getStatus(),
                "projectId", hunt.getProjectId(),
                "commitSha", hunt.getCommitSha()));
        return hunt;
    }
}
