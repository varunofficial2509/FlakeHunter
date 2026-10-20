package dev.flakehunter.orchestrator.hunt;

/** How a run is perturbed (tech doc section 9). The runner's entrypoint.sh applies it. */
enum Strategy {
    BASELINE,
    SHUFFLE_ORDER,
    RANDOM_SEED,
    PARALLEL,
    CPU_THROTTLE,
    TIMEZONE,
    ISOLATED
}
