package dev.flakehunter.orchestrator.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin AI panel: the kill switch, and how many findings wait for a diagnosis. */
@RestController
@RequestMapping("/api/admin/ai")
class AiAdminController {

    record AiView(boolean enabled, boolean available, int pendingFindings) {}

    record UpdateAi(@NotNull Boolean enabled) {}

    private final AiSwitch ai;
    private final JdbcClient jdbc;

    AiAdminController(AiSwitch ai, JdbcClient jdbc) {
        this.ai = ai;
        this.jdbc = jdbc;
    }

    @GetMapping
    AiView get() {
        // Findings of hunts still waiting on agents, minus those already diagnosed.
        int pending = jdbc.sql("""
                SELECT coalesce(sum(h.findings - (SELECT count(*) FROM hunt.diagnoses d WHERE d.hunt_id = h.id)), 0)
                FROM hunt.hunts h WHERE h.status IN ('AWAITING_AI', 'DIAGNOSING')""").query(Integer.class).single();
        return new AiView(ai.enabled(), ai.available(), pending);
    }

    @PutMapping
    ResponseEntity<?> update(@Valid @RequestBody UpdateAi request) {
        if (!ai.available()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT, "Agents run only in the Kubernetes deployment (deploy/k3d/up.sh)"));
        }
        ai.set(request.enabled());
        return ResponseEntity.ok(get());
    }
}
