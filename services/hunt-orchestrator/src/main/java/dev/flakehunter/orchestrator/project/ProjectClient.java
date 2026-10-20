package dev.flakehunter.orchestrator.project;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Reads a project from project-service. The orchestrator may not read the project schema (tech doc section 7), so
 * this is the one synchronous call between services, made once when a hunt starts.
 */
@Component
public class ProjectClient {

    /** The fields a hunt needs; project-service sends more, which are ignored. */
    public record Project(UUID id, String repoUrl, String buildTool, Integer jdkVersion, String testCommand) {}

    private final RestClient http;

    ProjectClient(RestClient.Builder builder, @Value("${flakehunter.project-service-url}") String baseUrl) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        // A hung project-service must fail POST /api/hunts quickly, not hold the request forever.
        timeouts.setConnectTimeout(Duration.ofSeconds(2));
        timeouts.setReadTimeout(Duration.ofSeconds(5));
        this.http = builder.baseUrl(baseUrl).requestFactory(timeouts).build();
    }

    /** Empty when the project doesn't exist. Any other failure (down, 5xx) throws RestClientException. */
    public Optional<Project> find(UUID id) {
        try {
            return Optional.ofNullable(http.get().uri("/api/projects/{id}", id).retrieve().body(Project.class));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) return Optional.empty();
            throw e;
        }
    }
}
