package dev.flakehunter.orchestrator;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling // runs OutboxRelay
@EnableAsync // runs HuntExecution in the background, off the HTTP request
public class HuntOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(HuntOrchestratorApplication.class, args);
    }

    // Probes and Prometheus scrapes hit /actuator every few seconds; tracing them would bury the real traces.
    @Bean
    ObservationPredicate noActuatorObservations() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && request.getCarrier().getRequestURI().startsWith("/actuator"));
    }
}
