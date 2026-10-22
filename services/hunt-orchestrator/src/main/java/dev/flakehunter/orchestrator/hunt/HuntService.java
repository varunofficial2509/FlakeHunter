package dev.flakehunter.orchestrator.hunt;

import dev.flakehunter.events.Topics;
import dev.flakehunter.orchestrator.ai.AiSwitch;
import dev.flakehunter.orchestrator.outbox.Outbox;
import dev.flakehunter.orchestrator.project.ProjectClient;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    private final Experiments experiments;
    private final AiSwitch ai;
    private final JdbcClient jdbc;

    HuntService(HuntRepository hunts, RunRepository runs, Outbox outbox, ProjectClient projects,
            ApplicationEventPublisher events, TransactionTemplate tx, Experiments experiments, AiSwitch ai,
            JdbcClient jdbc) {
        this.hunts = hunts;
        this.runs = runs;
        this.outbox = outbox;
        this.projects = projects;
        this.events = events;
        this.tx = tx;
        this.experiments = experiments;
        this.ai = ai;
        this.jdbc = jdbc;
    }

    /**
     * Called by the REST API now, and by the hunt.requested listener later. Empty if the project doesn't exist.
     * The budget is already checked against {@link HuntOptions}.
     */
    Optional<Hunt> start(CreateHuntRequest request, Budget budget) {
        // The HTTP call happens before the transaction, so no database connection waits on the network.
        return projects.find(request.projectId()).map(project -> tx.execute(status -> {
            Hunt hunt = hunts.save(request.toHunt(project, budget));
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
        List<Run> planned = runs.saveAll(RunPlanner.plan(huntId, hunt.getBudget(),
                ThreadLocalRandom.current())); // getDefault() needs jdk.random, which the JRE image lacks
        HuntStatus previous = hunt.getStatus();
        hunt.useImage(image);
        hunt.moveTo(HuntStatus.RUNNING);
        // result-aggregator needs the count to know when every run has reported.
        emit(hunt, previous, Map.of("plannedRuns", planned.size()));
        return planned;
    }

    /** Hunts an orchestrator drives itself; after a restart, nobody does until HuntExecution resumes them. */
    List<Hunt> unfinished() {
        return hunts.findByStatusIn(EnumSet.of(HuntStatus.CREATED, HuntStatus.PREPARING, HuntStatus.RUNNING));
    }

    Hunt find(UUID huntId) {
        return hunts.findById(huntId).orElseThrow(() -> new IllegalStateException("No hunt " + huntId));
    }

    List<Run> unfinishedRuns(UUID huntId) {
        return runs.findByHuntIdAndExperimentIdIsNullAndStatusIn(huntId, UNFINISHED);
    }

    @Transactional
    void markRunning(UUID runId) {
        runs.lockById(runId).filter(r -> r.getStatus() == Run.Status.SCHEDULED).ifPresent(Run::start);
    }

    /**
     * Records a run's outcome; when it was the hunt's last run, moves the hunt to ANALYZING, and when it was an
     * experiment's last run, answers the experiment. Idempotent.
     */
    @Transactional
    void finishRun(UUID runId, Run.Status result, List<String> failedTests) {
        Optional<Run> found = runs.lockById(runId);
        if (found.isEmpty()) {
            log.warn("Ignoring result for unknown run {}", runId); // e.g. a manual runner test
            return;
        }
        Run run = found.get();
        if (run.getStatus().isFinished()) return; // a duplicate, or the launcher already gave up on it
        Hunt hunt = lock(run.getHuntId());
        run.finish(result, failedTests);
        if (run.getExperimentId() != null) {
            experiments.runFinished(run);
        } else if (hunt.getStatus() == HuntStatus.RUNNING
                && runs.countByHuntIdAndExperimentIdIsNullAndStatusIn(hunt.getId(), UNFINISHED) == 0) {
            hunt.moveTo(HuntStatus.ANALYZING);
            emit(hunt, HuntStatus.RUNNING, Map.of());
        }
    }

    /** ANALYZING → COMPLETED. shortcut: goes to DIAGNOSING / AWAITING_AI instead once agents exist (Phase 3). */
    @Transactional
    void completeAnalysis(UUID huntId, int findings) {
        Hunt hunt = lock(huntId);
        if (hunt.getStatus() != HuntStatus.ANALYZING) return; // cancelled or failed meanwhile; keep that outcome
        hunt.analyzed(findings);
        // The findings wait in Kafka either way; with AI off nothing reads them until it is switched on.
        hunt.moveTo(findings == 0 ? HuntStatus.COMPLETED
                : ai.enabled() ? HuntStatus.DIAGNOSING : HuntStatus.AWAITING_AI);
        emit(hunt, HuntStatus.ANALYZING, Map.of("findings", findings));
    }

    /** Records one finding's finished agent session; the hunt completes with its last one. Idempotent. */
    @Transactional
    void diagnosed(UUID huntId, UUID findingId, String status) {
        Hunt hunt = lock(huntId);
        boolean first = jdbc.sql("INSERT INTO hunt.diagnoses (finding_id, hunt_id, status) VALUES (?, ?, ?)"
                + " ON CONFLICT DO NOTHING").params(findingId, huntId, status).update() == 1;
        int done = jdbc.sql("SELECT count(*) FROM hunt.diagnoses WHERE hunt_id = ?").param(huntId)
                .query(Integer.class).single();
        if (first && hunt.getStatus() == HuntStatus.DIAGNOSING && done >= hunt.getFindings()) {
            hunt.moveTo(HuntStatus.COMPLETED);
            emit(hunt, HuntStatus.DIAGNOSING, Map.of("diagnosed", done));
        }
    }

    // Inside AiSwitch.set's transaction: hunts that waited for AI are diagnosed now, from the Kafka backlog.
    @EventListener
    void onAiSwitched(AiSwitch.Switched event) {
        if (!event.enabled()) return; // running sessions pause with their pods; their hunts stay DIAGNOSING
        for (Hunt waiting : hunts.findByStatusIn(EnumSet.of(HuntStatus.AWAITING_AI))) {
            moveTo(waiting.getId(), HuntStatus.DIAGNOSING);
        }
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
