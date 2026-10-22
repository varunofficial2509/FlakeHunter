package dev.flakehunter.orchestrator.hunt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verify;

import dev.flakehunter.events.AnalysisCompleted;
import dev.flakehunter.events.DiagnosisCompleted;
import dev.flakehunter.events.EventEnvelope;
import dev.flakehunter.events.Topics;
import dev.flakehunter.orchestrator.TestcontainersConfiguration;
import dev.flakehunter.orchestrator.ai.AgentScaler;
import dev.flakehunter.orchestrator.project.ProjectClient;
import dev.flakehunter.orchestrator.runner.Runner;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

/** The AI kill switch: findings of a hunt wait while AI is off, and the hunt completes once all are diagnosed. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AiSwitchTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    HuntRepository hunts;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    @MockitoBean
    AgentScaler scaler; // as in the cluster: agents are available

    @MockitoBean
    Runner runners;

    @MockitoBean
    ProjectClient projects;

    @Test
    void findingsWaitWhileAiIsOffAndTheHuntCompletesWhenAllAreDiagnosed() throws Exception {
        switchAi(false);
        verify(scaler).scale(false);
        int pendingBefore = pending();
        Hunt hunt = analyzingHunt();
        send(Topics.ANALYSIS_COMPLETED, hunt.getId(), new AnalysisCompleted(10, 40, 2));
        await().atMost(Duration.ofSeconds(15)).until(() -> status(hunt) == HuntStatus.AWAITING_AI);
        assertThat(pending()).isEqualTo(pendingBefore + 2);

        switchAi(true);
        assertThat(status(hunt)).isEqualTo(HuntStatus.DIAGNOSING);

        UUID first = UUID.randomUUID();
        send(Topics.DIAGNOSIS_COMPLETED, hunt.getId(), diagnosis(first));
        send(Topics.DIAGNOSIS_COMPLETED, hunt.getId(), diagnosis(first)); // a redelivered session reports again
        await().atMost(Duration.ofSeconds(15)).until(() -> pending() == pendingBefore + 1);
        assertThat(status(hunt)).isEqualTo(HuntStatus.DIAGNOSING);

        send(Topics.DIAGNOSIS_COMPLETED, hunt.getId(), diagnosis(UUID.randomUUID()));
        await().atMost(Duration.ofSeconds(15)).until(() -> status(hunt) == HuntStatus.COMPLETED);
    }

    private void switchAi(boolean enabled) {
        assertThat(mvc.put().uri("/api/admin/ai").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\": " + enabled + "}")).hasStatusOk()
                .bodyJson().extractingPath("$.enabled").isEqualTo(enabled);
    }

    private int pending() throws Exception {
        String body = mvc.get().uri("/api/admin/ai").exchange().getResponse().getContentAsString();
        return json.readTree(body).get("pendingFindings").asInt();
    }

    private Hunt analyzingHunt() {
        Hunt hunt = new Hunt(UUID.randomUUID(), HuntExecutionTests.SHA, new Budget(10, 2),
                new ProjectClient.Project(UUID.randomUUID(), "https://github.com/alibaba/fastjson", "MAVEN", 8, null));
        for (HuntStatus next : List.of(HuntStatus.PREPARING, HuntStatus.RUNNING, HuntStatus.ANALYZING)) {
            hunt.moveTo(next);
        }
        return hunts.save(hunt);
    }

    private static DiagnosisCompleted diagnosis(UUID findingId) {
        return new DiagnosisCompleted(findingId, "UNKNOWN", null, null, "nothing proven", null, List.of());
    }

    private void send(String topic, UUID huntId, Object payload) throws Exception {
        kafka.send(topic, huntId.toString(), json.writeValueAsString(EventEnvelope.of(topic, huntId, payload))).get();
    }

    private HuntStatus status(Hunt hunt) {
        return hunts.findById(hunt.getId()).orElseThrow().getStatus();
    }
}
