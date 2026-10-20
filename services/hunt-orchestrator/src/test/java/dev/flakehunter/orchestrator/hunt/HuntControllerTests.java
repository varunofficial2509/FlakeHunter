package dev.flakehunter.orchestrator.hunt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.flakehunter.orchestrator.TestcontainersConfiguration;
import dev.flakehunter.orchestrator.outbox.Outbox;
import dev.flakehunter.orchestrator.project.ProjectClient;
import dev.flakehunter.orchestrator.runner.DockerRunner;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.IllegalTransactionStateException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HuntControllerTests {

    static final String SHA = "e05e9c5e4be580691cc55a59f3256595393203a1";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ConsumerFactory<String, String> consumers;

    @Autowired
    JsonMapper json;

    @Autowired
    Outbox outbox;

    // project-service and Docker are outside this service; HuntExecutionTests covers what happens with them.
    @MockitoBean
    ProjectClient projects;

    @MockitoBean
    DockerRunner docker;

    @BeforeEach
    void machineFitsFourRunners() {
        when(docker.machine()).thenReturn(new DockerRunner.Machine(8_392_605_696L, 10, 1L << 30));
    }

    @BeforeEach
    void projectExists() {
        when(projects.find(any())).thenAnswer(call -> Optional.of(new ProjectClient.Project(
                call.getArgument(0), "https://github.com/alibaba/fastjson", "MAVEN", 8, "-Djdk.version=1.8")));
    }

    @Test
    void createsAHuntAndPublishesItsStatus() {
        UUID projectId = UUID.randomUUID();
        MvcTestResult created = post("""
                {"projectId": "%s", "commitSha": "%s"}""".formatted(projectId, SHA));

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).bodyJson().extractingPath("$.status").isEqualTo("CREATED");
        assertThat(created).bodyJson().extractingPath("$.budget.maxRuns").isEqualTo(40);
        String location = created.getResponse().getHeader("Location");
        String huntId = location.substring(location.lastIndexOf('/') + 1);
        assertThat(mvc.get().uri(location)).hasStatusOk()
                .bodyJson().extractingPath("$.commitSha").isEqualTo(SHA);

        // The relay runs every second; the event must reach Kafka keyed by huntId, wrapped in the envelope.
        ConsumerRecord<String, String> record = firstRecordWithKey("hunt.status", huntId);
        JsonNode event = json.readTree(record.value());
        assertThat(event.get("eventType").asString()).isEqualTo("hunt.status");
        assertThat(event.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(event.get("huntId").asString()).isEqualTo(huntId);
        assertThat(event.get("payload").get("status").asString()).isEqualTo("CREATED");
        assertThat(event.get("payload").get("projectId").asString()).isEqualTo(projectId.toString());
    }

    @Test
    void acceptsACustomBudget() {
        MvcTestResult created = post("""
                {"projectId": "%s", "commitSha": "%s", "budget": {"maxRuns": 10, "maxParallelPods": 2}}"""
                .formatted(UUID.randomUUID(), SHA));

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).bodyJson().extractingPath("$.budget.maxParallelPods").isEqualTo(2);
    }

    @Test
    void listsTheHuntsOfOneProject() {
        UUID projectId = UUID.randomUUID();
        post("""
                {"projectId": "%s", "commitSha": "%s"}""".formatted(projectId, SHA));
        post("""
                {"projectId": "%s", "commitSha": "%s"}""".formatted(UUID.randomUUID(), SHA));

        assertThat(mvc.get().uri("/api/hunts").param("projectId", projectId.toString())).hasStatusOk()
                .bodyJson().extractingPath("$[*].projectId").asArray().containsExactly(projectId.toString());
    }

    @Test
    void offersAndEnforcesTheBudgetsThisMachineCanRun() {
        assertThat(mvc.get().uri("/api/hunts/options")).hasStatusOk()
                .bodyJson().extractingPath("$.maxParallelPods").asArray().containsExactly(1, 2, 4);

        MvcTestResult tooMany = post("""
                {"projectId": "%s", "commitSha": "%s", "budget": {"maxRuns": 40, "maxParallelPods": 8}}"""
                .formatted(UUID.randomUUID(), SHA));
        assertThat(tooMany).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(tooMany).bodyJson().extractingPath("$.errors.budget").asString().contains("[1, 2, 4]");
    }

    @Test
    void rejectsInvalidInputAsProblemDetail() {
        MvcTestResult result = post("""
                {"commitSha": "--upload-pack=x", "budget": {"maxRuns": 2, "maxParallelPods": 1}}""");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyText().contains("projectId", "commitSha", "budget.maxRuns");
    }

    @Test
    void unknownProjectIsRejected() {
        when(projects.find(any())).thenReturn(Optional.empty());

        MvcTestResult result = post("""
                {"projectId": "%s", "commitSha": "%s"}""".formatted(UUID.randomUUID(), SHA));

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result).bodyText().contains("not found");
    }

    @Test
    void unknownHuntIsNotFound() {
        assertThat(mvc.get().uri("/api/hunts/00000000-0000-0000-0000-000000000000"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void outboxRefusesToWriteOutsideATransaction() {
        assertThatThrownBy(() -> outbox.add("hunt.status", UUID.randomUUID(), Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private MvcTestResult post(String body) {
        return mvc.post().uri("/api/hunts").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private ConsumerRecord<String, String> firstRecordWithKey(String topic, String key) {
        Properties fromStart = new Properties();
        fromStart.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer =
                consumers.createConsumer("test-" + UUID.randomUUID(), null, null, fromStart)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plusSeconds(20);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(r.key())) return r;
                }
            }
        }
        throw new AssertionError("No " + topic + " event with key " + key + " within 20s");
    }
}
