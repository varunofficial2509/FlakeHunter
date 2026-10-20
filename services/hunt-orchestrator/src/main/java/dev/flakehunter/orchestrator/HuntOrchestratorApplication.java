package dev.flakehunter.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // runs OutboxRelay
public class HuntOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(HuntOrchestratorApplication.class, args);
    }
}
