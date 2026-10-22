package dev.flakehunter.orchestrator.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.flakehunter.orchestrator.TestcontainersConfiguration;
import dev.flakehunter.orchestrator.project.ProjectClient;
import dev.flakehunter.orchestrator.runner.Runner;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/** An event keeps the trace of the transaction that added it, although the relay sends it later. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxTracingTests {

    static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    static final String TOPIC = "outbox.trace-test";

    @Autowired
    Outbox outbox;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ConsumerFactory<String, String> consumers;

    // Same mocks as the hunt tests, so Spring reuses their context and containers.
    @MockitoBean
    ProjectClient projects;

    @MockitoBean
    Runner runners;

    @Test
    void sendsTheEventInsideTheTraceThatAddedIt() {
        UUID key = UUID.randomUUID();
        SpanContext caller = SpanContext.create(TRACE_ID, "b7ad6b7169203331", TraceFlags.getSampled(),
                TraceState.getDefault());
        try (Scope ignored = Context.root().with(Span.wrap(caller)).makeCurrent()) {
            tx.executeWithoutResult(s -> outbox.add(TOPIC, UUID.randomUUID(), key, Map.of("hello", "trace")));
        }

        assertThat(jdbc.sql("SELECT traceparent FROM hunt.outbox WHERE key = ?").param(key.toString())
                .query(String.class).single()).isEqualTo("00-" + TRACE_ID + "-b7ad6b7169203331-01");
        try (Consumer<String, String> consumer = consumers.createConsumer("trace-test-" + key, null)) {
            consumer.subscribe(List.of(TOPIC));
            ConsumerRecord<String, String> sent = await().atMost(Duration.ofSeconds(15)).until(() -> {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (r.key().equals(key.toString())) return r;
                }
                return null;
            }, r -> r != null);
            // The producer span is a child of the caller: same trace, its own span id.
            assertThat(new String(sent.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8))
                    .startsWith("00-" + TRACE_ID + "-");
        }
    }
}
