package com.svatsa.escrow.consumers;

import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Regression for chaos K2 (chaos/FINDINGS.md #45): the Kafka consumer loops must run on platform
 * threads although the app enables virtual threads. On virtual threads the Kafka client pins its
 * carrier (it blocks inside synchronized code during a group rejoin), and two pinned consumers
 * starved a 2-CPU container completely after a kill -9.
 *
 * Thread.getAllStackTraces() lists platform threads only: with the old configuration it found no
 * consumer loop at all, because every one ran on a virtual thread.
 */
@DisplayName("Kafka consumers run on platform threads")
class KafkaConsumerThreadsIT extends AbstractIntegrationTest {

    @Autowired private KafkaListenerEndpointRegistry registry;

    @Value("${spring.threads.virtual.enabled}") private boolean virtualThreadsEnabled;

    @Test
    @DisplayName("every listener container's consumer loop is a platform thread, with virtual threads on")
    void consumersRunOnPlatformThreads() {
        assertThat(virtualThreadsEnabled).as("the app runs with virtual threads, which is the case that matters").isTrue();
        assertThat(registry.getListenerContainers()).isNotEmpty();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            List<Thread> loops = consumerLoops();
            assertThat(loops).as("platform threads running a Kafka consumer loop").hasSizeGreaterThanOrEqualTo(registry.getListenerContainers().size());
            assertThat(loops).allSatisfy(t -> {
                assertThat(t.isVirtual()).isFalse();
                assertThat(t.getName()).startsWith("kafka-consumer-");
            });
        });
    }

    private static List<Thread> consumerLoops() {
        return Thread.getAllStackTraces().entrySet().stream()
            .filter(e -> Arrays.stream(e.getValue()).anyMatch(f ->
                f.getClassName().endsWith("KafkaMessageListenerContainer$ListenerConsumer") && f.getMethodName().equals("run")))
            .map(Map.Entry::getKey)
            .toList();
    }
}
