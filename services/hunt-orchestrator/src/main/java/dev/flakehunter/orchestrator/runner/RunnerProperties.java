package dev.flakehunter.orchestrator.runner;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** {@code flakehunter.runner.*} in application.yaml. */
@ConfigurationProperties("flakehunter.runner")
public record RunnerProperties(
        String imagePrefix,
        String network,
        String kafkaBootstrapServers,
        String mavenCacheVolume,
        Duration prepareTimeout,
        Duration runTimeout,
        // Hard limit per container; its JVMs size their heaps to fit inside it.
        DataSize memory,
        // All runner containers together never use more, whatever a hunt's budget asks for.
        DataSize totalMemory) {

    /** How many containers fit in totalMemory at once (at least one). */
    public int maxContainers() {
        return (int) Math.max(1, totalMemory.toBytes() / memory.toBytes());
    }
}
