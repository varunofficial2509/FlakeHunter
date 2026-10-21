package dev.flakehunter.runner.publisher;

import dev.flakehunter.events.EventEnvelope;
import dev.flakehunter.events.RunCompleted;
import dev.flakehunter.events.SourcesExported;
import dev.flakehunter.events.TestResult;
import dev.flakehunter.events.TestResult.Status;
import dev.flakehunter.events.Topics;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Usage: {@code publisher <workspace> <test-exit-code> <duration-ms>}, with HUNT_ID, RUN_ID, STRATEGY, SEED and
 * KAFKA_BOOTSTRAP_SERVERS in the environment. Exits 0 once every event is acknowledged by Kafka.
 * {@code publisher --sources <workspace>}, run by prepare.sh for an experiment, publishes sources.exported for
 * the comma-separated test ids in EXPORT_TESTS instead; HUNT_ID is then the experiment's id.
 */
public final class Publisher {

    /** Exit code of coreutils {@code timeout} when it killed the command. */
    static final int TIMEOUT_EXIT_CODE = 124;

    record Run(UUID huntId, UUID runId, String strategy, Long seed) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Publisher() {}

    public static void main(String[] args) {
        if (args.length == 2 && args[0].equals("--sources")) {
            exportSources(Path.of(args[1]));
            return;
        }
        if (args.length != 3) {
            System.err.println("usage: publisher <workspace> <test-exit-code> <duration-ms>");
            System.exit(2);
        }
        Run run = new Run(UUID.fromString(env("HUNT_ID")), UUID.fromString(env("RUN_ID")), env("STRATEGY"),
                System.getenv("SEED") == null ? null : Long.parseLong(System.getenv("SEED")));
        List<SurefireReports.Case> cases = SurefireReports.read(Path.of(args[0]));
        try (Producer<String, String> producer = producer()) {
            publish(producer, run, cases, Integer.parseInt(args[1]), Long.parseLong(args[2]));
        } catch (Exception e) {
            System.err.println("Publishing failed: " + e);
            System.exit(1); // the Job fails, so the orchestrator sees the run never reported
        }
    }

    private static void exportSources(Path workspace) {
        UUID experimentId = UUID.fromString(env("HUNT_ID"));
        Map<String, String> files = Sources.collect(workspace, List.of(env("EXPORT_TESTS").split(",")));
        try (Producer<String, String> producer = producer()) {
            send(producer, Topics.SOURCES_EXPORTED, experimentId.toString(), experimentId,
                    new SourcesExported(experimentId, files)).get();
            System.out.println("Exported " + files.keySet());
        } catch (Exception e) {
            System.err.println("Exporting sources failed: " + e);
            System.exit(1);
        }
    }

    private static Producer<String, String> producer() {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, env("KAFKA_BOOTSTRAP_SERVERS"),
                // Tech doc section 6: a send counts once every replica has it, and retries never duplicate.
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.LINGER_MS_CONFIG, 20); // results arrive as a burst; batch them
        return new KafkaProducer<>(config, new StringSerializer(), new StringSerializer());
    }

    /** Sends one test.results per case, waits for all acks, then sends run.completed. */
    static RunCompleted publish(Producer<String, String> producer, Run run, List<SurefireReports.Case> cases,
            int exitCode, long durationMs) throws Exception {
        List<Future<RecordMetadata>> sends = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            SurefireReports.Case c = cases.get(i);
            TestResult result = new TestResult(run.runId(), c.testId(), run.strategy(), run.seed(), c.status(),
                    c.durationMs(), c.failureMessage(), c.stackHash(), i);
            // Keyed by huntId:testId so every outcome of one test lands on one partition (tech doc section 6).
            sends.add(send(producer, Topics.TEST_RESULTS, run.huntId() + ":" + c.testId(), run.huntId(), result));
        }
        producer.flush();
        for (Future<RecordMetadata> sent : sends) sent.get(); // throws if any result was not stored

        RunCompleted completed = summarize(run, cases, exitCode, durationMs);
        send(producer, Topics.RUN_COMPLETED, run.huntId().toString(), run.huntId(), completed).get();
        return completed;
    }

    static RunCompleted summarize(Run run, List<SurefireReports.Case> cases, int exitCode, long durationMs) {
        RunCompleted.Status status;
        if (exitCode == TIMEOUT_EXIT_CODE) status = RunCompleted.Status.TIMED_OUT;
        // No reports and a failing build: it crashed (compile error, bad JDK, OOM) before running any test.
        else if (cases.isEmpty() && exitCode != 0) status = RunCompleted.Status.FAILED;
        else status = RunCompleted.Status.SUCCEEDED;
        return new RunCompleted(run.runId(), run.strategy(), run.seed(), status, exitCode, durationMs,
                cases.size(), count(cases, Status.PASSED), count(cases, Status.FAILED), count(cases, Status.ERROR),
                count(cases, Status.SKIPPED), cases.stream()
                        .filter(c -> c.status() == Status.FAILED || c.status() == Status.ERROR)
                        .map(SurefireReports.Case::testId).limit(RunCompleted.MAX_FAILED_TESTS).toList());
    }

    private static Future<RecordMetadata> send(Producer<String, String> producer, String topic, String key,
            UUID huntId, Object payload) {
        String value = JSON.writeValueAsString(EventEnvelope.of(topic, huntId, payload));
        return producer.send(new ProducerRecord<>(topic, key, value));
    }

    private static int count(List<SurefireReports.Case> cases, Status status) {
        return (int) cases.stream().filter(c -> c.status() == status).count();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.err.println("Missing environment variable " + name);
            System.exit(2);
        }
        return value;
    }
}
