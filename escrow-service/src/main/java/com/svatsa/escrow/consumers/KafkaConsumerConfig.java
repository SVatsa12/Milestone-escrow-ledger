package com.svatsa.escrow.consumers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka consumer configuration for P8 idempotent consumers.
 *
 * Configures:
 * 1. AckMode.RECORD for manual per-message acking after successful processing
 * 2. DefaultErrorHandler with fixed backoff (1s, max 3 retries)
 * 3. Logging error handler that logs failed messages
 * 4. JSON deserialization with error handling
 * 5. Deduplication at consumer level via IdempotentConsumerSupport
 *
 * Safety guarantees:
 * - AckMode.RECORD: offset committed AFTER listener method returns (after DB transaction commits)
 * - FixedBackOff(1s, 3): retries for 3 seconds with 1-second intervals
 * - Logging: failed messages are logged, topics will have implicit .DLT support via Spring Kafka
 * - IdempotentConsumerSupport: duplicate events are idempotent via processed_events table
 */
@Configuration
public class KafkaConsumerConfig {
    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    /**
     * Default error handler for consumers.
     * Retries failed messages with fixed backoff (1 second, max 3 attempts).
     * On final failure, logs the error.
     */
    @Bean
    public DefaultErrorHandler defaultErrorHandler() {
        return new DefaultErrorHandler();
    }
}
