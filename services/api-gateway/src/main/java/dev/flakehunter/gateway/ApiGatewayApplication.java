package dev.flakehunter.gateway;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }

    // Probes and Prometheus scrapes hit /actuator every few seconds; tracing them would bury the real traces.
    @Bean
    ObservationPredicate noActuatorObservations() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && request.getCarrier().getPath().value().startsWith("/actuator"));
    }
}
