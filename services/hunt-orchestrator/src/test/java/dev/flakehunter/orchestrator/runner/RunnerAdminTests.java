package dev.flakehunter.orchestrator.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.flakehunter.orchestrator.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RunnerAdminTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    Runner runners;

    @BeforeEach
    void dockerHas8GbAnd10Cpus() {
        when(runners.machine()).thenReturn(new Runner.Machine(8L << 30, 10, 1L << 30));
        when(runners.removeMavenCache()).thenReturn(true);
    }

    @AfterEach
    void backToDefaults() {
        jdbc.sql("DELETE FROM hunt.system_settings").update();
        jdbc.sql("DELETE FROM hunt.hunts").update();
    }

    @Test
    void showsTheDefaultsAndOnlyOptionsThatFitDocker() {
        assertThat(mvc.get().uri("/api/admin/runner")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("""
                        {"memoryMb": 1536, "totalMemoryMb": 6144, "memoryMbOptions": [1024, 1536, 2048, 3072],
                         "totalMemoryMbOptions": [1024, 2048, 3072, 4096, 5120, 6144, 7168], "dockerCpus": 10}""");
    }

    @Test
    void savesAChoiceAndRejectsOneThatDoesNotFit() {
        assertThat(put("{\"memoryMb\": 2048, \"totalMemoryMb\": 4096}")).hasStatusOk();
        assertThat(mvc.get().uri("/api/admin/runner")).bodyJson().extractingPath("$.memoryMb").isEqualTo(2048);

        assertThat(put("{\"memoryMb\": 2048, \"totalMemoryMb\": 16384}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(put("{\"memoryMb\": 3072, \"totalMemoryMb\": 2048}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(put("{\"memoryMb\": 2048}")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refusesToClearTheMavenCacheWhileAHuntRuns() {
        jdbc.sql("""
                INSERT INTO hunt.hunts (id, project_id, commit_sha, status, budget)
                VALUES (?, ?, 'e05e9c5e4be580691cc55a59f3256595393203a1', 'RUNNING', '{}')""")
                .params(UUID.randomUUID(), UUID.randomUUID()).update();

        assertThat(mvc.delete().uri("/api/admin/runner/maven-cache")).hasStatus(HttpStatus.CONFLICT);
        verify(runners, never()).removeMavenCache();
    }

    @Test
    void clearsTheMavenCacheWhenIdle() {
        assertThat(mvc.delete().uri("/api/admin/runner/maven-cache")).hasStatus(HttpStatus.NO_CONTENT);
        verify(runners).removeMavenCache();
    }

    private MvcTestResult put(String body) {
        return mvc.put().uri("/api/admin/runner").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }
}
