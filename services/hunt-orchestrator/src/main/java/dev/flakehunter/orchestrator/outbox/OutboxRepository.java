package dev.flakehunter.orchestrator.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    // SKIP LOCKED: a second orchestrator replica takes other rows instead of waiting or sending the same ones.
    // Native SQL ignores hibernate.default_schema, so the schema is written out.
    @Query(value = """
            SELECT * FROM hunt.outbox WHERE published_at IS NULL
            ORDER BY seq LIMIT :limit FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<OutboxEvent> lockUnpublished(int limit);
}
