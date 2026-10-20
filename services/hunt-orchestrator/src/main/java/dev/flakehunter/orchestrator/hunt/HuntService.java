package dev.flakehunter.orchestrator.hunt;

import dev.flakehunter.events.Topics;
import dev.flakehunter.orchestrator.outbox.Outbox;
import dev.flakehunter.orchestrator.project.ProjectClient;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every hunt state change. Each one locks the hunt, changes it, and writes its hunt.status event in the same
 * transaction (outbox), so the database and Kafka always agree.
 */
@Service
class HuntService {

    /** Published inside the creating transaction; HuntExecution picks it up after the commit. */
    record HuntCreated(UUID huntId) {}

    private static final Logger log = LoggerFactory.getLogger(HuntService.class);
    private static final EnumSet<Run.Status> UNFINISHED = EnumSet.of(Run.Status.SCHEDULED, Run.Status.RUNNING);

    private final HuntRepository hunts;
    private final RunRepository runs;
    private final Outbox outbox;
    private final ProjectClient projects;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    HuntService(HuntRepository hunts, RunRepository runs, Outbox outbox, ProjectClient projects,
            ApplicationEventPublisher events, TransactionTemplate tx) {
        this.hunts = hunts;
        this.runs = runs;
        this.outbox = outbox;
        this.projects = projects;
        this.events = events;
        this.tx = tx;
    }

    /** Called by the REST API now, and by the hunt.requested listener later. Empty if the project doesn't exist. */
    Optional<Hunt> start(CreateHuntRequest request) {
        // The HTTP call happens before the transaction, so no database connection waits on the network.
        return projects.find(request.projectId()).map(project -> tx.execute(status -> {
            Hunt hunt = hunts.save(request.toHunt(project));
            emit(hunt, null, Map.of("projectId", hunt.getProjectId(), "commitSha", hunt.getCommitSha()));
            events.publishEvent(new HuntCreated(hunt.getId()));
            return hunt;
        }));
    }

    @Transactional
    Hunt moveTo(UUID huntId, HuntStatus next) {
        Hunt hunt = lock(huntId);
        HuntStatus previous = hunt.getStatus();
        hunt.moveTo(next);
        emit(hunt, previous, Map.of());
        return hunt;
    }

    @Transactional
    void fail(UUID huntId, String reason) {
        Hunt hunt = lock(huntId);
        if (!hunt.getStatus().canMoveTo(HuntStatus.FAILED)) return; // already over; keep the first outcome
        HuntStatus previous = hunt.getStatus();
        hunt.fail(reason);
        emit(hunt, previous, Map.of("reason", reason));
    }

    /** Plans the runs and moves the hunt to RUNNING, in one transaction. */
    @Transactional
    List<Run> startRuns(UUID huntId, String image) {
        Hunt hunt = lock(huntId);
        List<Run> planned = runs.saveAll(RunPlanner.plan(huntId, hunt.getBudget(), RandomGenerator.getDefault()));
        HuntStatus previous = hunt.getStatus();
        hunt.useImage(image);
        hunt.moveTo(HuntStatus.RUNNING);
        // result-aggregator needs the count to know when every run has reported.
        emit(hunt, previous, Map.of("plannedRuns", planned.size()));
        return planned;
    }

    @Transactional
    void markRunning(UUID runId) {
        runs.lockById(runId).filter(r -> r.getStatus() == Run.Status.SCHEDULED).ifPresent(Run::start);
    }

    /** Records a run's outcome; when it was the hunt's last run, moves the hunt to ANALYZING. Idempotent. */
    @Transactional
    void finishRun(UUID runId, Run.Status result) {
        Optional<Run> found = runs.lockById(runId);
        if (found.isEmpty()) {
            log.warn("Ignoring result for unknown run {}", runId); // e.g. a manual runner test
            return;
        }
        Run run = found.get();
        if (run.getStatus().isFinished()) return; // a duplicate, or the launcher already gave up on it
        Hunt hunt = lock(run.getHuntId());
        run.finish(result);
        if (hunt.getStatus() == HuntStatus.RUNNING
                && runs.countByHuntIdAndStatusIn(hunt.getId(), UNFINISHED) == 0) {
            hunt.moveTo(HuntStatus.ANALYZING);
            emit(hunt, HuntStatus.RUNNING, Map.of());
        }
    }

    /** ANALYZING → COMPLETED. shortcut: goes to DIAGNOSING / AWAITING_AI instead once agents exist (Phase 3). */
    @Transactional
    void completeAnalysis(UUID huntId, int findings) {
        Hunt hunt = lock(huntId);
        if (hunt.getStatus() != HuntStatus.ANALYZING) return; // cancelled or failed meanwhile; keep that outcome
        hunt.moveTo(HuntStatus.COMPLETED);
        emit(hunt, HuntStatus.ANALYZING, Map.of("findings", findings));
    }

    private Hunt lock(UUID huntId) {
        return hunts.lockById(huntId).orElseThrow(() -> new IllegalStateException("No hunt " + huntId));
    }

    private void emit(Hunt hunt, HuntStatus previous, Map<String, ?> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", hunt.getStatus());
        if (previous != null) payload.put("previous", previous);
        payload.putAll(details);
        outbox.add(Topics.HUNT_STATUS, hunt.getId(), payload);
    }
}
