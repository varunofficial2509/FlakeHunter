package dev.flakehunter.aggregator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes rows nobody needs any more, so the outbox and test_results don't grow forever. */
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
        int outbox = jdbc.sql("DELETE FROM results.outbox WHERE published_at < now() - interval '7 days'").update();
        // The findings keep their evidence; raw results are read once, by the Analyzer.
        // shortcut: experiment runs report under the experiment's id, which has no hunts row, so their results
        // stay; give test_results a timestamp if that ever matters.
        int results = jdbc.sql("""
                DELETE FROM results.test_results WHERE hunt_id IN (
                  SELECT id FROM results.hunts WHERE analyzed_at < now() - interval '30 days')""").update();
        if (outbox + results > 0) log.info("Housekeeping removed {} outbox rows, {} test results", outbox, results);
    }
}
