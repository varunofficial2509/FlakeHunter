package dev.flakehunter.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes bookkeeping rows nobody needs any more, so the outbox and processed_events don't grow forever. */
@Component
class Housekeeping {

    private static final Logger log = LoggerFactory.getLogger(Housekeeping.class);

    private final JdbcClient jdbc;

    Housekeeping(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Every 6 hours rather than at a fixed time of day: a laptop is often asleep at 3 a.m.
    @Scheduled(initialDelay = 60_000, fixedDelay = 6 * 60 * 60 * 1000)
    void cleanUp() {
        // Only published rows: an unpublished one is an event Kafka hasn't received yet.
        int outbox = jdbc.sql("DELETE FROM hunt.outbox WHERE published_at < now() - interval '7 days'").update();
        // Kafka deletes events after 14 days (compose.yaml), so an older id can never be redelivered.
        int processed = jdbc.sql("DELETE FROM hunt.processed_events WHERE processed_at < now() - interval '15 days'")
                .update();
        if (outbox + processed > 0) log.info("Housekeeping removed {} outbox rows, {} processed event ids", outbox, processed);
    }
}
