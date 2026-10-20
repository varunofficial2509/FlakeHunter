package dev.flakehunter.orchestrator.runner;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

/** Runner memory chosen in the Admin panel, falling back to application.yaml. A hunt reads it once, at its start. */
@Component
public class RunnerSettings {

    public record Limits(
            // Hard limit per container; its JVMs size their heaps to fit inside it.
            DataSize memory,
            // All runner containers of a hunt together never use more, whatever its budget asks for.
            DataSize totalMemory) {

        /** How many containers fit in totalMemory at once (at least one). */
        public int maxContainers() {
            return (int) Math.max(1, totalMemory.toBytes() / memory.toBytes());
        }
    }

    private static final String MEMORY = "runner.memory-mb";
    private static final String TOTAL = "runner.total-memory-mb";

    private final JdbcClient jdbc;
    private final RunnerProperties defaults;

    RunnerSettings(JdbcClient jdbc, RunnerProperties defaults) {
        this.jdbc = jdbc;
        this.defaults = defaults;
    }

    public Limits current() {
        Map<String, Long> saved = jdbc.sql("SELECT key, value FROM hunt.system_settings WHERE key IN (?, ?)")
                .params(MEMORY, TOTAL)
                .query((rs, row) -> Map.entry(rs.getString("key"), Long.parseLong(rs.getString("value"))))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return new Limits(
                saved.containsKey(MEMORY) ? DataSize.ofMegabytes(saved.get(MEMORY)) : defaults.memory(),
                saved.containsKey(TOTAL) ? DataSize.ofMegabytes(saved.get(TOTAL)) : defaults.totalMemory());
    }

    @Transactional
    void save(Limits limits) {
        put(MEMORY, limits.memory().toMegabytes());
        put(TOTAL, limits.totalMemory().toMegabytes());
    }

    private void put(String key, long value) {
        jdbc.sql("""
                INSERT INTO hunt.system_settings (key, value) VALUES (?, ?)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""")
                .params(key, String.valueOf(value)).update();
    }
}
