package dev.flakehunter.orchestrator.hunt;

import dev.flakehunter.events.ExperimentCompleted;
import dev.flakehunter.events.ExperimentRequested;
import dev.flakehunter.events.Topics;
import dev.flakehunter.orchestrator.outbox.Outbox;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Experiment and verification state changes. Plans and patches come from an LLM, so every rule is checked here
 * before anything runs (tech doc section 8, "No direct execution"): a broken rule is answered with REJECTED.
 * Experiments are answered on experiment.completed, verifications on fix.verified.
 */
@Service
class Experiments {

    /** Published inside the accepting transaction; HuntExecution runs the experiment after the commit. */
    record Accepted(Experiment experiment) {}

    static final int MAX_PER_FINDING = 4;
    static final int MAX_REPEAT = 30;
    static final int MAX_TESTS = 3;
    // Tech doc section 8: one fix, then one more round after a failed verification.
    static final int MAX_VERIFICATIONS_PER_FINDING = 2;
    static final int MAX_VERIFY_REPEAT = 100;
    static final int MAX_PATCH_CHARS = 64_000; // goes into the prepare container's environment
    // Surefire -Dtest entries: Class or Class#method. No commas, globs or spaces, so one entry is one test.
    private static final Pattern TEST = Pattern.compile("[A-Za-z_$][\\w$.]*(#[A-Za-z_$][\\w$]*)?");
    // RANDOM_SEED and ISOLATED are not wired in the runner yet (RunPlanner).
    private static final Set<String> STRATEGIES =
            Set.of("BASELINE", "SHUFFLE_ORDER", "TIMEZONE", "PARALLEL", "CPU_THROTTLE");
    // A hunt still running its own runs would count experiment runs as its own.
    private static final Set<HuntStatus> TESTABLE = EnumSet.of(HuntStatus.ANALYZING, HuntStatus.COMPLETED,
            HuntStatus.AWAITING_AI, HuntStatus.DIAGNOSING, HuntStatus.VERIFYING);
    private static final EnumSet<Run.Status> UNFINISHED = EnumSet.of(Run.Status.SCHEDULED, Run.Status.RUNNING);

    private final ExperimentRepository experiments;
    private final HuntRepository hunts;
    private final RunRepository runs;
    private final Outbox outbox;
    private final ApplicationEventPublisher events;

    Experiments(ExperimentRepository experiments, HuntRepository hunts, RunRepository runs, Outbox outbox,
            ApplicationEventPublisher events) {
        this.experiments = experiments;
        this.hunts = hunts;
        this.runs = runs;
        this.outbox = outbox;
        this.events = events;
    }

    /**
     * Records the request with its runs and starts it after the commit, or rejects it. Idempotent. verification
     * says which topic it came on: only verification.requested may carry a patch.
     */
    @Transactional
    void accept(UUID huntId, ExperimentRequested request, boolean verification) {
        if (experiments.existsById(request.experimentId())) return; // asked again
        Optional<Hunt> hunt = hunts.lockById(huntId); // also serializes the per-finding counts below
        String problem = hunt.isEmpty() ? "No hunt " + huntId : rejection(hunt.get(), request, verification);
        if (problem != null) {
            // An unknown hunt can't own an experiment row; the agent still gets its answer.
            if (hunt.isPresent()) experiments.save(new Experiment(huntId, request, Experiment.Status.REJECTED, problem));
            answer(verification, huntId, request.findingId(), request.experimentId(),
                    ExperimentCompleted.Status.REJECTED, problem, 0, List.of(), null);
            return;
        }
        Experiment experiment = experiments.save(
                new Experiment(huntId, request, Experiment.Status.PREPARING, null));
        ExperimentRequested.Plan plan = request.plan();
        Strategy strategy = Strategy.valueOf(plan.strategy());
        List<Run> planned = new ArrayList<>();
        for (int i = 0; i < plan.repeat(); i++) {
            Long seed = strategy == Strategy.SHUFFLE_ORDER ? ThreadLocalRandom.current().nextLong(1, 1_000_000) : null;
            planned.add(new Run(huntId, strategy, seed, plan.timezone(), experiment.getId(),
                    String.join(",", plan.tests())));
        }
        runs.saveAll(planned);
        events.publishEvent(new Accepted(experiment));
    }

    @Transactional
    void markRunning(UUID experimentId) {
        experiments.findById(experimentId).filter(e -> e.getStatus() == Experiment.Status.PREPARING)
                .ifPresent(e -> e.moveTo(Experiment.Status.RUNNING, null));
    }

    @Transactional
    void fail(UUID experimentId, String reason) {
        Experiment experiment = experiments.findById(experimentId).orElseThrow();
        if (isOver(experiment)) return;
        experiment.moveTo(Experiment.Status.FAILED, reason);
        for (Run run : runs.findByExperimentId(experimentId)) {
            if (!run.getStatus().isFinished()) run.finish(Run.Status.FAILED, null);
        }
        answer(experiment, ExperimentCompleted.Status.FAILED, reason, 0, List.of());
    }

    /**
     * Keeps the files an experiment's prepare step exported, for its answer.
     * shortcut: if this arrives after the experiment's last run (it is sent before any run starts, so only a
     * stalled listener could do that), the answer goes out without sources; resend them then if it matters.
     */
    @Transactional
    void exported(UUID experimentId, Map<String, String> files) {
        experiments.findById(experimentId).ifPresent(e -> e.exported(files));
    }

    /** Called by HuntService.finishRun inside its transaction: after the experiment's last run, answer. */
    void runFinished(Run run) {
        UUID experimentId = run.getExperimentId();
        Experiment experiment = experiments.findById(experimentId).orElseThrow();
        List<Run> all = runs.findByExperimentId(experimentId);
        if (isOver(experiment) || all.stream().anyMatch(r -> UNFINISHED.contains(r.getStatus()))) return;
        // Runs that crashed or timed out report nothing about the tests, so they are left out of the counts.
        List<Run> reported = all.stream().filter(r -> r.getStatus() == Run.Status.SUCCEEDED).toList();
        List<ExperimentCompleted.TestOutcome> outcomes = experiment.getPlan().tests().stream()
                .map(test -> new ExperimentCompleted.TestOutcome(test,
                        (int) reported.stream().filter(r -> failed(r, test)).count()))
                .toList();
        experiment.moveTo(Experiment.Status.COMPLETED, null);
        answer(experiment, ExperimentCompleted.Status.COMPLETED, null, reported.size(), outcomes);
    }

    List<Experiment> unfinished() {
        return experiments.findByStatusIn(EnumSet.of(Experiment.Status.PREPARING, Experiment.Status.RUNNING));
    }

    List<Run> unfinishedRuns(UUID experimentId) {
        return runs.findByExperimentId(experimentId).stream()
                .filter(r -> UNFINISHED.contains(r.getStatus())).toList();
    }

    private String rejection(Hunt hunt, ExperimentRequested request, boolean verification) {
        ExperimentRequested.Plan plan = request.plan();
        if (!TESTABLE.contains(hunt.getStatus())) return "Hunt is " + hunt.getStatus() + ", not testable";
        if (plan == null || plan.tests() == null || plan.tests().isEmpty() || plan.tests().size() > MAX_TESTS) {
            return "Give 1 to " + MAX_TESTS + " tests";
        }
        for (String test : plan.tests()) {
            if (test == null || !TEST.matcher(test).matches()) return "Not a test id: " + test;
        }
        if (!STRATEGIES.contains(plan.strategy())) return "Strategy must be one of " + STRATEGIES;
        if (plan.strategy().equals("TIMEZONE") != (plan.timezone() != null)) {
            return "Give a timezone with TIMEZONE, and only then";
        }
        if (plan.timezone() != null) {
            try {
                ZoneId.of(plan.timezone());
            } catch (DateTimeException e) {
                return "Unknown timezone " + plan.timezone();
            }
        }
        if (!verification) {
            if (request.patch() != null) return "Only verification.requested may carry a patch";
            if (plan.repeat() < 1 || plan.repeat() > MAX_REPEAT) return "repeat must be 1 to " + MAX_REPEAT;
            if (experiments.countByFindingIdAndPatchIsNull(request.findingId()) >= MAX_PER_FINDING) {
                return "This finding already had " + MAX_PER_FINDING + " experiments";
            }
            return null;
        }
        if (request.patch() == null || request.patch().isBlank()) return "A verification needs a patch";
        if (request.patch().length() > MAX_PATCH_CHARS) return "The patch is over " + MAX_PATCH_CHARS + " characters";
        if (plan.repeat() < 1 || plan.repeat() > MAX_VERIFY_REPEAT) return "repeat must be 1 to " + MAX_VERIFY_REPEAT;
        if (experiments.countByFindingIdAndPatchIsNotNull(request.findingId()) >= MAX_VERIFICATIONS_PER_FINDING) {
            return "This finding already had " + MAX_VERIFICATIONS_PER_FINDING + " verifications";
        }
        return null;
    }

    /** A test id from the plan matches a failed method itself, or any method of a planned class. */
    private static boolean failed(Run run, String test) {
        List<String> failed = run.getFailedTests() == null ? List.of() : run.getFailedTests();
        return failed.stream().anyMatch(id -> id.equals(test) || id.startsWith(test + "#"));
    }

    private static boolean isOver(Experiment experiment) {
        return !EnumSet.of(Experiment.Status.PREPARING, Experiment.Status.RUNNING).contains(experiment.getStatus());
    }

    private void answer(Experiment experiment, ExperimentCompleted.Status status, String reason, int reportedRuns,
            List<ExperimentCompleted.TestOutcome> outcomes) {
        answer(experiment.isVerification(), experiment.getHuntId(), experiment.getFindingId(), experiment.getId(),
                status, reason, reportedRuns, outcomes, experiment.getSources());
    }

    private void answer(boolean verification, UUID huntId, UUID findingId, UUID experimentId,
            ExperimentCompleted.Status status, String reason, int reportedRuns,
            List<ExperimentCompleted.TestOutcome> outcomes, Map<String, String> sources) {
        outbox.add(verification ? Topics.FIX_VERIFIED : Topics.EXPERIMENT_COMPLETED, huntId, findingId,
                new ExperimentCompleted(findingId, experimentId, status, reason, reportedRuns, outcomes, sources));
    }
}
