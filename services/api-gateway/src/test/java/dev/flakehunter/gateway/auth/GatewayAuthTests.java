package dev.flakehunter.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flakehunter.admin.password=test-password")
class GatewayAuthTests {

    // Stands in for project-service: answers every request with an empty JSON list.
    static HttpServer downstream;

    @LocalServerPort
    int port;

    WebTestClient client;

    @DynamicPropertySource
    static void routeToStub(DynamicPropertyRegistry registry) throws IOException {
        downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        downstream.createContext("/", exchange -> {
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        downstream.start();
        // Random per run, so no secret-looking literal sits in the repo.
        registry.add("flakehunter.jwt.secret", GatewayAuthTests::randomSecret);
        registry.add("PROJECT_SERVICE_URL", () -> "http://127.0.0.1:" + downstream.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        downstream.stop(0);
    }

    @BeforeEach
    void client() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void rejectsRequestsWithoutAToken() {
        client.get().uri("/api/projects").exchange().expectStatus().isUnauthorized();
        // Probes and Prometheus can't log in; the ingress keeps /actuator out of reach from outside.
        client.get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
    }

    @Test
    void rejectsAWrongPassword() {
        login("admin", "nope").expectStatus().isUnauthorized();
        login("nope", "test-password").expectStatus().isUnauthorized();
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() {
        // Well-formed HS256 token for the admin, signed with a different secret.
        SecurityConfig config = new SecurityConfig();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("admin")
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        String forged = config.jwtEncoder(config.jwtKey(randomSecret()))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        client.get().uri("/api/projects").header("Authorization", "Bearer " + forged)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void forwardsRequestsThatCarryALoginToken() {
        LoginController.TokenResponse body = login("admin", "test-password")
                .expectStatus().isOk()
                .expectBody(LoginController.TokenResponse.class).returnResult().getResponseBody();
        assertThat(body.expiresAt()).isAfter(Instant.now());

        client.get().uri("/api/projects").header("Authorization", "Bearer " + body.token())
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("[]");
    }

    private static String randomSecret() {
        return UUID.randomUUID() + "-" + UUID.randomUUID();
    }

    private WebTestClient.ResponseSpec login(String username, String password) {
        return client.post().uri("/api/auth/login")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", password))
                .exchange();
    }
}
