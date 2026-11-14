package com.svatsa.escrow.outbox;

import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay's producer keeps per-partition order across retries: idempotence on, acks=all, at most
 * 5 requests in flight. Checked on the config Kafka actually uses (ProducerConfig applies defaults
 * and the idempotence rules), built from the application's real ProducerFactory.
 */
@DisplayName("Outbox producer config")
class OutboxProducerConfigIT extends AbstractIntegrationTest {

    @Autowired private ProducerFactory<String, String> producerFactory;

    @Test
    @DisplayName("enable.idempotence=true, acks=all, max.in.flight.requests.per.connection <= 5")
    void producerKeepsOrderAcrossRetries() {
        Map<String, Object> configured = new HashMap<>(producerFactory.getConfigurationProperties());
        ProducerConfig effective = new ProducerConfig(configured);

        assertThat(effective.getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isTrue();
        assertThat(effective.getString(ProducerConfig.ACKS_CONFIG)).isIn("all", "-1");
        assertThat(effective.getInt(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION)).isBetween(1, 5);

        // Explicit, not just Kafka's default: application.yml used to put enable.idempotence where
        // Boot ignores it.
        assertThat(configured).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        assertThat(configured).containsKey(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION);
    }
}
