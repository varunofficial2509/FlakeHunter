package dev.flakehunter.aggregator;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // runs Analyzer and OutboxRelay
public class ResultAggregatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResultAggregatorApplication.class, args);
    }

    // Probes and Prometheus scrapes hit /actuator every few seconds; tracing them would bury the real traces.
    @Bean
    ObservationPredicate noActuatorObservations() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && request.getCarrier().getRequestURI().startsWith("/actuator"));
    }
}
