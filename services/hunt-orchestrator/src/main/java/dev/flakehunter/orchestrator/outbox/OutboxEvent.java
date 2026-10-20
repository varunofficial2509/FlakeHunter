package dev.flakehunter.orchestrator.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One event waiting to be published, or already published (published_at set). */
@Entity
@Table(name = "outbox")
class OutboxEvent {

    @Id
    private UUID id;

    @Column(insertable = false, updatable = false) // filled by Postgres (identity column)
    private Long seq;

    private String topic;
    private String key;

    // Already-serialized envelope; the relay sends it as-is.
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    private Instant createdAt;
    private Instant publishedAt;

    protected OutboxEvent() {} // for JPA

    OutboxEvent(UUID id, String topic, String key, String payload) {
        this.id = id;
        this.topic = topic;
        this.key = key;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    void markPublished() {
        publishedAt = Instant.now();
    }

    UUID getId() { return id; }
    String getTopic() { return topic; }
    String getKey() { return key; }
    String getPayload() { return payload; }
    Instant getPublishedAt() { return publishedAt; }
}
