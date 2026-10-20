package dev.flakehunter.orchestrator;

import dev.flakehunter.events.Topics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/**
 * Tech doc section 11: a message that keeps failing is retried 3 times, then parked on {@code <topic>.dlq} with the
 * error in its headers, so one bad message can't block its partition. Boot wires this handler into every listener.
 */
@Configuration
class KafkaErrors {

    @Bean
    DeadLetterPublishingRecoverer deadLetters(KafkaTemplate<String, String> kafka) {
        // Partition -1: let Kafka pick by key, so the .dlq topic needs only one partition.
        return new DeadLetterPublishingRecoverer(kafka,
                (record, e) -> new TopicPartition(record.topic() + Topics.DLQ_SUFFIX, -1));
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer deadLetters) {
        DefaultErrorHandler handler = new DefaultErrorHandler(deadLetters, new FixedBackOff(1000, 3));
        handler.addNotRetryableExceptions(JacksonException.class); // unreadable JSON stays unreadable
        return handler;
    }
}
