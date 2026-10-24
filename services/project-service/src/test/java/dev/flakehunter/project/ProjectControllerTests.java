package dev.flakehunter.project;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProjectControllerTests {

    @Autowired
    MockMvcTester mvc;

    @Test
    void createsAndReadsAProject() {
        MvcTestResult created = post("""
                {"name": "fastjson", "repoUrl": "https://github.com/alibaba/fastjson-create", "buildTool": "MAVEN",
                 "jdkVersion": 8}""");

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).bodyJson().extractingPath("$.defaultBranch").isEqualTo("main");
        String location = created.getResponse().getHeader("Location");

        assertThat(mvc.get().uri(location)).hasStatusOk()
                .bodyJson().extractingPath("$.repoUrl").isEqualTo("https://github.com/alibaba/fastjson-create");
        assertThat(mvc.get().uri("/api/projects")).hasStatusOk()
                .bodyJson().extractingPath("$[*].name").asArray().contains("fastjson");
    }

    @Test
    void patchChangesOnlyGivenFields() {
        String location = post("""
                {"name": "before", "repoUrl": "https://github.com/acme/patch-me", "buildTool": "MAVEN"}""")
                .getResponse().getHeader("Location");

        MvcTestResult patched = mvc.patch().uri(location).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"after\", \"jdkVersion\": 17}").exchange();

        assertThat(patched).hasStatusOk();
        assertThat(patched).bodyJson().extractingPath("$.name").isEqualTo("after");
        assertThat(patched).bodyJson().extractingPath("$.jdkVersion").isEqualTo(17);
        assertThat(patched).bodyJson().extractingPath("$.buildTool").isEqualTo("MAVEN");
    }

    @Test
    void rejectsInvalidInputAsProblemDetail() {
        MvcTestResult result = post("""
                {"name": "x", "repoUrl": "file:///etc/passwd", "buildTool": "MAVEN", "defaultBranch": "--upload-pack=x",
                 "jdkVersion": 9}""");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyText().contains("repoUrl", "defaultBranch", "jdkVersion must be 8, 11, 17 or 21");
    }

    @Test
    void rejectsDuplicateRepository() {
        String body = """
                {"name": "dup", "repoUrl": "https://github.com/acme/dup", "buildTool": "MAVEN"}""";
        assertThat(post(body)).hasStatus(HttpStatus.CREATED);

        assertThat(post(body)).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void unknownProjectIsNotFound() {
        assertThat(mvc.get().uri("/api/projects/00000000-0000-0000-0000-000000000000"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    private MvcTestResult post(String json) {
        return mvc.post().uri("/api/projects").contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }
}
