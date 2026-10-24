package dev.flakehunter.aggregator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

// Same annotations as AnalysisFlowTests, so Spring reuses its context (see DeadLetterTests).
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ResultAggregatorApplicationTests {

    @Test
    void contextLoads() {}
}
