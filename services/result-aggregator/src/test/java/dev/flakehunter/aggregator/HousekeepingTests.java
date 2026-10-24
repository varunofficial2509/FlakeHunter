package dev.flakehunter.aggregator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

// Same annotations as AnalysisFlowTests, so Spring reuses its context (see DeadLetterTests).
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HousekeepingTests {

    @Autowired
    Housekeeping housekeeping;

    @Autowired
    JdbcClient jdbc;

    @Test
    void deletesOnlyOldPublishedEventsAndResultsOfLongAnalyzedHunts() {
        UUID oldPublished = outboxRow("now() - interval '8 days'");
        UUID oldUnpublished = outboxRow("NULL");
        UUID recentPublished = outboxRow("now() - interval '1 day'");
        UUID oldHunt = hunt("now() - interval '31 days'");
        UUID recentHunt = hunt("now() - interval '1 day'");
        UUID runningHunt = hunt("NULL");

        housekeeping.cleanUp();

        assertThat(count("outbox", "id", oldPublished)).isZero();
        assertThat(count("outbox", "id", oldUnpublished)).isOne(); // never delete what Kafka hasn't got
        assertThat(count("outbox", "id", recentPublished)).isOne();
        assertThat(count("test_results", "hunt_id", oldHunt)).isZero();
        assertThat(count("test_results", "hunt_id", recentHunt)).isOne();
        assertThat(count("test_results", "hunt_id", runningHunt)).isOne();
    }

    private UUID outboxRow(String publishedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO results.outbox (id, topic, key, payload, published_at) VALUES (?, 'finding.detected',"
                + " 'k', '{}', " + publishedAt + ")").param(id).update();
        return id;
    }

    /** A hunt analyzed at analyzedAt (NULL: not yet), with one stored result. */
    private UUID hunt(String analyzedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO results.hunts (id, analyzed_at) VALUES (?, " + analyzedAt + ")").param(id).update();
        jdbc.sql("""
                INSERT INTO results.test_results (run_id, test_id, hunt_id, strategy, status, duration_ms, order_index)
                VALUES (?, 'a.A#t', ?, 'BASELINE', 'PASSED', 1, 0)""").params(UUID.randomUUID(), id).update();
        return id;
    }

    private int count(String table, String column, UUID id) {
        return jdbc.sql("SELECT count(*) FROM results." + table + " WHERE " + column + " = ?").param(id)
                .query(Integer.class).single();
    }
}
