package dev.flakehunter.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Single-user login: checks the admin credentials from config and returns a signed JWT. */
@RestController
class LoginController {

    record LoginRequest(String username, String password) {}

    record TokenResponse(String token, Instant expiresAt) {}

    private final JwtEncoder encoder;
    private final String username;
    private final String password;
    private final Duration ttl;

    LoginController(
            JwtEncoder encoder,
            @Value("${flakehunter.admin.username}") String username,
            @Value("${flakehunter.admin.password}") String password,
            @Value("${flakehunter.jwt.ttl}") Duration ttl) {
        this.encoder = encoder;
        this.username = username;
        this.password = password;
        this.ttl = ttl;
    }

    @PostMapping("/api/auth/login")
    TokenResponse login(@RequestBody LoginRequest request) {
        // Non-short-circuit & so a wrong username takes as long as a wrong password.
        if (!(same(username, request.username()) & same(password, request.password()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong username or password");
        }
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(username)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new TokenResponse(token, claims.getExpiresAt());
    }

    private static boolean same(String expected, String given) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                (given == null ? "" : given).getBytes(StandardCharsets.UTF_8));
    }
}
