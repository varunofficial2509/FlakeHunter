package dev.flakehunter.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flakehunter.orchestrator.project.ProjectClient;
import dev.flakehunter.orchestrator.runner.Runner;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class HousekeepingTests {

    @Autowired
    Housekeeping housekeeping;

    @Autowired
    JdbcClient jdbc;

    // Same mocks as the hunt tests, so Spring reuses their context and containers.
    @MockitoBean
    ProjectClient projects;

    @MockitoBean
    Runner runners;

    @Test
    void deletesOnlyOldPublishedEventsAndOldEventIds() {
        UUID oldPublished = outboxRow("now() - interval '8 days'", "now() - interval '8 days'");
        UUID oldUnpublished = outboxRow("now() - interval '8 days'", "NULL");
        UUID recentPublished = outboxRow("now() - interval '1 day'", "now() - interval '1 day'");
        UUID oldEvent = processedEvent("now() - interval '20 days'");
        UUID recentEvent = processedEvent("now() - interval '1 day'");

        housekeeping.cleanUp();

        assertThat(exists("hunt.outbox", "id", oldPublished)).isFalse();
        assertThat(exists("hunt.outbox", "id", oldUnpublished)).isTrue(); // never delete what Kafka hasn't got
        assertThat(exists("hunt.outbox", "id", recentPublished)).isTrue();
        assertThat(exists("hunt.processed_events", "event_id", oldEvent)).isFalse();
        assertThat(exists("hunt.processed_events", "event_id", recentEvent)).isTrue();
    }

    private UUID outboxRow(String createdAt, String publishedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO hunt.outbox (id, topic, key, payload, created_at, published_at) VALUES (?, 'hunt.status',"
                + " 'k', '{}', " + createdAt + ", " + publishedAt + ")").param(id).update();
        return id;
    }

    private UUID processedEvent(String processedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO hunt.processed_events (event_id, processed_at) VALUES (?, " + processedAt + ")")
                .param(id).update();
        return id;
    }

    private boolean exists(String table, String column, UUID id) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = ?").param(id)
                .query(Integer.class).single() == 1;
    }
}
