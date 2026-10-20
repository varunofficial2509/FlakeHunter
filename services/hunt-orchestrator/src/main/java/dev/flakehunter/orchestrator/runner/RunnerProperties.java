package dev.flakehunter.orchestrator.runner;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code flakehunter.runner.*} in application.yaml. */
@ConfigurationProperties("flakehunter.runner")
public record RunnerProperties(
        String imagePrefix,
        String network,
        String kafkaBootstrapServers,
        String mavenCacheVolume,
        Duration prepareTimeout,
        Duration runTimeout) {}
