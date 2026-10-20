package dev.flakehunter.aggregator.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.flakehunter.aggregator.TestcontainersConfiguration;
import dev.flakehunter.events.EventEnvelope;
import dev.flakehunter.events.TestResult;
import dev.flakehunter.events.Topics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Poison messages land on {@code <topic>.dlq} and don't hold up the good ones. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DeadLetterTests {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ConsumerFactory<String, String> consumers;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Test
    void unreadableEventIsParkedWithTheError() throws Exception {
        String key = UUID.randomUUID().toString();
        kafka.send(Topics.RUN_COMPLETED, key, "not json").get();

        ConsumerRecord<String, String> parked = firstRecordWithKey(Topics.RUN_COMPLETED + Topics.DLQ_SUFFIX, key);
        assertThat(parked.value()).isEqualTo("not json");
        assertThat(new String(parked.headers().lastHeader("kafka_dlt-exception-fqcn").value(),
                StandardCharsets.UTF_8)).contains("Exception");
    }

    @Test
    void badTestResultIsParkedAndTheRestOfTheBatchIsStored() throws Exception {
        UUID hunt = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        String badKey = hunt + ":bad";
        kafka.send(Topics.TEST_RESULTS, badKey, "{broken").get();
        kafka.send(Topics.TEST_RESULTS, hunt + ":good", json.writeValueAsString(EventEnvelope.of(Topics.TEST_RESULTS,
                hunt, new TestResult(run, "com.acme.T#good", "BASELINE", null, TestResult.Status.PASSED, 5, null,
                        null, 0)))).get();

        assertThat(firstRecordWithKey(Topics.TEST_RESULTS + Topics.DLQ_SUFFIX, badKey).value()).isEqualTo("{broken");
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc
                .sql("SELECT count(*) FROM results.test_results WHERE hunt_id = ?").param(hunt)
                .query(Integer.class).single() == 1);
    }

    private ConsumerRecord<String, String> firstRecordWithKey(String topic, String key) {
        Properties fromStart = new Properties();
        fromStart.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer =
                consumers.createConsumer("test-" + UUID.randomUUID(), null, null, fromStart)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(r.key())) return r;
                }
            }
        }
        throw new AssertionError("No record with key " + key + " on " + topic);
    }
}
