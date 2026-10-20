package dev.flakehunter.runner.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flakehunter.events.RunCompleted;
import dev.flakehunter.events.TestResult.Status;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PublisherTests {

    final UUID huntId = UUID.randomUUID();
    final Publisher.Run run = new Publisher.Run(huntId, UUID.randomUUID(), "SHUFFLE_ORDER", 4211L);
    final JsonMapper json = JsonMapper.builder().build();

    final List<SurefireReports.Case> cases = List.of(
            new SurefireReports.Case("a.ATest#ok", Status.PASSED, 10, null, null),
            new SurefireReports.Case("a.ATest#flaky", Status.FAILED, 20, "expected 1 but was 2", "9f1c"));

    @Test
    void publishesEachResultThenRunCompleted() throws Exception {
        MockProducer<String, String> producer = autoAckProducer();

        RunCompleted completed = Publisher.publish(producer, run, cases, 1, 5000);

        List<ProducerRecord<String, String>> sent = producer.history();
        assertThat(sent).extracting(ProducerRecord::topic)
                .containsExactly("test.results", "test.results", "run.completed");
        assertThat(sent.get(1).key()).isEqualTo(huntId + ":a.ATest#flaky");
        assertThat(sent.get(2).key()).isEqualTo(huntId.toString());

        JsonNode result = json.readTree(sent.get(1).value());
        assertThat(result.get("eventType").asString()).isEqualTo("test.results");
        assertThat(result.get("occurredAt").isString()).isTrue(); // ISO-8601, not epoch numbers
        assertThat(result.get("payload").get("status").asString()).isEqualTo("FAILED");
        assertThat(result.get("payload").get("seed").asLong()).isEqualTo(4211);
        assertThat(result.get("payload").get("orderIndex").asInt()).isEqualTo(1);

        // Exit code 1 because a test failed: the run itself still succeeded.
        assertThat(completed.status()).isEqualTo(RunCompleted.Status.SUCCEEDED);
        assertThat(completed.failed()).isEqualTo(1);
        assertThat(completed.passed()).isEqualTo(1);
    }

    @Test
    void crashedBuildStillReportsTheRun() throws Exception {
        MockProducer<String, String> producer = autoAckProducer();

        RunCompleted completed = Publisher.publish(producer, run, List.of(), 1, 900);

        assertThat(producer.history()).extracting(ProducerRecord::topic).containsExactly("run.completed");
        assertThat(completed.status()).isEqualTo(RunCompleted.Status.FAILED);
        assertThat(completed.total()).isZero();
    }

    @Test
    void timeoutIsReportedWithPartialResults() {
        RunCompleted completed = Publisher.summarize(run, cases, Publisher.TIMEOUT_EXIT_CODE, 1_800_000);

        assertThat(completed.status()).isEqualTo(RunCompleted.Status.TIMED_OUT);
        assertThat(completed.total()).isEqualTo(2);
    }

    @Test
    void doesNotSendRunCompletedWhenAResultWasNotStored() {
        MockProducer<String, String> producer = new MockProducer<>(false, null,
                new StringSerializer(), new StringSerializer()) {
            @Override
            public synchronized void flush() {
                errorNext(new RuntimeException("broker down"));
            }
        };

        assertThatThrownBy(() -> Publisher.publish(producer, run, cases, 0, 10)).hasMessageContaining("broker down");
        assertThat(producer.history()).extracting(ProducerRecord::topic).doesNotContain("run.completed");
    }

    private static MockProducer<String, String> autoAckProducer() {
        return new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
    }
}
