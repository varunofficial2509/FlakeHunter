package dev.flakehunter.orchestrator.ai;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The AI kill switch (tech doc section 8). Detection never needs it; only agent sessions stop. */
@Service
public class AiSwitch {

    /** Published inside the switching transaction, so hunts change state with the setting or not at all. */
    public record Switched(boolean enabled) {}

    private static final Logger log = LoggerFactory.getLogger(AiSwitch.class);
    private static final String KEY = "ai.enabled";

    private final JdbcClient jdbc;
    private final Optional<AgentScaler> scaler;
    private final ApplicationEventPublisher events;

    AiSwitch(JdbcClient jdbc, Optional<AgentScaler> scaler, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.scaler = scaler;
        this.events = events;
    }

    /** False under Compose, which runs no agents. */
    public boolean available() {
        return scaler.isPresent();
    }

    /** On unless an admin switched it off. */
    public boolean enabled() {
        return available() && jdbc.sql("SELECT value FROM hunt.system_settings WHERE key = ?").param(KEY)
                .query(String.class).optional().map(Boolean::parseBoolean).orElse(true);
    }

    /** Scaling last: if Kubernetes refuses, the setting and the hunts roll back with it. */
    @Transactional
    void set(boolean enabled) {
        jdbc.sql("""
                INSERT INTO hunt.system_settings (key, value) VALUES (?, ?)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""")
                .params(KEY, String.valueOf(enabled)).update();
        events.publishEvent(new Switched(enabled));
        scaler.orElseThrow().scale(enabled);
    }

    // A Helm upgrade or a failed commit can leave the ScaledObject out of step with the setting.
    @EventListener(ApplicationReadyEvent.class)
    void reconcile() {
        scaler.ifPresent(s -> {
            try {
                s.scale(enabled());
            } catch (RuntimeException e) {
                log.warn("Could not apply the AI switch to agent-service", e);
            }
        });
    }
}
