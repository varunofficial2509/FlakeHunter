package dev.flakehunter.orchestrator.hunt;

import dev.flakehunter.orchestrator.runner.Runner;
import dev.flakehunter.orchestrator.runner.RunnerSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drives one hunt in the background: prepare once, then run every planned run, at most maxParallelPods at a
 * time. Run results arrive separately, through HuntEventsListener.
 * shortcut: an orchestrator restart abandons hunts in PREPARING or RUNNING; the Phase 2 reconciler repairs them.
 */
@Component
class HuntExecution {

    private static final Logger log = LoggerFactory.getLogger(HuntExecution.class);

    private final HuntService hunts;
    private final Runner runners;
    private final RunnerSettings settings;

    HuntExecution(HuntService hunts, Runner runners, RunnerSettings settings) {
        this.hunts = hunts;
        this.runners = runners;
        this.settings = settings;
    }

    // After commit: the background work must never start for a hunt whose transaction rolled back.
    @Async
    @TransactionalEventListener
    void onHuntCreated(HuntService.HuntCreated event) {
        UUID huntId = event.huntId();
        try {
            execute(huntId);
        } catch (Exception e) {
            log.error("Hunt {} failed", huntId, e);
            hunts.fail(huntId, "Internal error: " + e.getMessage());
        } finally {
            runners.removeVolume(huntId); // every container using it has exited by now
        }
    }

    private void execute(UUID huntId) throws InterruptedException {
        Hunt hunt = hunts.moveTo(huntId, HuntStatus.PREPARING);
        String problem = unsupported(hunt);
        if (problem != null) {
            hunts.fail(huntId, problem);
            return;
        }
        int jdk = hunt.getJdkVersion();
        // Read once: an Admin change applies to the next hunt, never halfway through this one.
        RunnerSettings.Limits limits = settings.current();
        int exit = runners.prepare(new Runner.PrepareJob(
                huntId, jdk, hunt.getRepoUrl(), hunt.getCommitSha(), hunt.getMavenArgs(), limits.memory()));
        if (exit != 0) {
            hunts.fail(huntId, switch (exit) {
                case Runner.TIMED_OUT -> "Preparing timed out";
                case Runner.KILLED -> "Preparing ran out of memory (limit " + limits.memory().toMegabytes() + " MB)";
                default -> "Preparing failed (exit " + exit + "): clone or test-compile did not succeed."
                        + " Check the commit SHA, jdkVersion and testCommand.";
            });
            return;
        }
        launchAll(hunt, hunts.startRuns(huntId, runners.image(jdk)), limits);
    }

    /**
     * Starts every run, never more than maxParallelPods at once (and never more than fit in the runners' total
     * memory), and returns when all containers exited.
     * shortcut: the memory cap is per hunt; two hunts at once can use twice the total. Fine for one user.
     */
    private void launchAll(Hunt hunt, List<Run> runs, RunnerSettings.Limits limits) throws InterruptedException {
        int parallel = Math.min(hunt.getBudget().maxParallelPods(), limits.maxContainers());
        if (parallel < hunt.getBudget().maxParallelPods()) {
            log.info("Hunt {}: running {} containers at once, not {}: that is all the runner memory allows",
                    hunt.getId(), parallel, hunt.getBudget().maxParallelPods());
        }
        Semaphore slots = new Semaphore(parallel);
        List<Thread> containers = new ArrayList<>();
        for (Run run : runs) {
            slots.acquire();
            hunts.markRunning(run.getId());
            containers.add(Thread.ofVirtual().start(() -> {
                try {
                    int exit = runners.run(new Runner.RunJob(hunt.getId(), run.getId(), hunt.getJdkVersion(),
                            run.getStrategy().name(), run.getSeed(), run.getTimezone(), hunt.getMavenArgs(),
                            limits.memory()));
                    // Exit 0 means the publisher delivered run.completed, and the listener records it.
                    // Anything else means no report is coming, so don't let the hunt wait for it.
                    if (exit == Runner.KILLED) {
                        log.warn("Run {} was killed, most likely by its {} MB memory limit", run.getId(),
                                limits.memory().toMegabytes());
                    }
                    if (exit != 0) {
                        log.warn("Run {} exited {} without reporting", run.getId(), exit);
                        hunts.finishRun(run.getId(),
                                exit == Runner.TIMED_OUT ? Run.Status.TIMED_OUT : Run.Status.FAILED);
                    }
                } catch (Exception e) {
                    log.error("Run {} could not be started", run.getId(), e);
                    hunts.finishRun(run.getId(), Run.Status.FAILED);
                } finally {
                    slots.release();
                }
            }));
        }
        for (Thread container : containers) container.join();
    }

    /** Problems we can report before spending minutes on a clone. Null when the hunt can go ahead. */
    private static String unsupported(Hunt hunt) {
        if (!"MAVEN".equals(hunt.getBuildTool())) return "Only Maven projects are supported in v1";
        // shortcut: no JDK detection from the pom yet (ADR 0003); the project must set it.
        if (hunt.getJdkVersion() == null) return "Set jdkVersion on the project (8, 11, 17 or 21)";
        return null;
    }
}
