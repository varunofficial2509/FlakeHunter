package dev.flakehunter.aggregator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // runs Analyzer and OutboxRelay
public class ResultAggregatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResultAggregatorApplication.class, args);
    }
}
