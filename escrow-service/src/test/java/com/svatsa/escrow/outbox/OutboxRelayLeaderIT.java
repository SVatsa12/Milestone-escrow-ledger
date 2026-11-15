package com.svatsa.escrow.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Two relays against the same Postgres and Kafka: the application's scheduled relay (A) and a
 * second instance built here (B), ticking 10x as often. Only the holder of the advisory lock
 * publishes, per-aggregate order holds, and killing the leader's database session hands leadership
 * to the other relay within a few ticks.
 *
 * Before the leader lock, two relays could reorder an aggregate's events (chaos/FINDINGS.md #51).
 */
@DisplayName("Outbox relay leader lock")
class OutboxRelayLeaderIT extends AbstractIntegrationTest {

    private static final int AGGREGATES = 20;
    private static final int EVENTS = 1_000;
    private static final int EVENTS_AFTER_HANDOVER = 200;
    private static final long B_TICK_MS = 20;      // A ticks every 200 ms (poll-interval)

    @Autowired private OutboxRelay relayA;
    @Autowired private OutboxWriter outboxWriter;
    @Autowired private TransactionTemplate tx;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private DataSource dataSource;
    @Autowired private KafkaProperties kafkaProperties;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("only the leader publishes, order holds across 1,000 events, and a killed leader hands over")
    void onlyTheLeaderPublishesAndAKilledLeaderHandsOver() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        String topic = "relay-leader-test-" + run;
        String aggregateType = "RelayLeaderTest";
        try (AdminClient admin = AdminClient.create(kafkaProperties.buildAdminProperties(null))) {
            admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get();
        }

        await("relay A leads").atMost(Duration.ofSeconds(10)).until(relayA::isLeader);

        SimpleMeterRegistry metricsB = new SimpleMeterRegistry();
        OutboxRelay relayB = new OutboxRelay(jdbcClient, kafkaTemplate, objectMapper, tx, metricsB, clock,
            dataSource, 100, Duration.ofSeconds(5));
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService ticker = Executors.newSingleThreadExecutor();
        Future<?> bLoop = ticker.submit(() -> {
            while (!stop.get()) {
                relayB.runOnce();
                try {
                    Thread.sleep(B_TICK_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        int[] nextSeq = new int[AGGREGATES];
        try {
            // --- 1. 1,000 events while both relays tick: A leads, B never publishes -------------
            writeEvents(aggregateType, run, topic, EVENTS, nextSeq);
            awaitOutboxDrained();

            assertThat(relayA.isLeader()).isTrue();
            assertThat(relayB.isLeader()).isFalse();
            assertThat(metricsB.counter("outbox.published").count()).as("events B published as a follower").isZero();
            assertThat(metricsB.get("outbox.relay.leader").gauge().value()).isZero();
            assertThat(meterRegistry.get("outbox.relay.leader").gauge().value()).isEqualTo(1.0);
            assertPerAggregateOrder(topic, run, EVENTS);

            // --- 2. kill the leader's database session: B takes over within a few ticks ---------
            long handoverMs = -1;
            for (int attempt = 0; attempt < 5 && !relayB.isLeader(); attempt++) {
                long pid = leaderPid();
                long killedAt = System.nanoTime();
                assertThat(jdbcClient.sql("SELECT pg_terminate_backend(?::int)").param(pid).query(Boolean.class).single()).isTrue();
                await("a new leader after killing session " + pid).atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(10))
                    .until(() -> relayA.isLeader() != relayB.isLeader() && leaderPidOrZero() != 0 && leaderPidOrZero() != pid);
                handoverMs = (System.nanoTime() - killedAt) / 1_000_000;
                // A ticks 10x less often than B, so B nearly always wins; if A re-won, kill again.
            }
            assertThat(relayB.isLeader()).as("B leads after the leader's session was killed").isTrue();
            await("A notices it lost leadership").atMost(Duration.ofSeconds(2)).until(() -> !relayA.isLeader());
            assertThat(handoverMs).as("handover time, ms (A ticks every 200 ms)").isBetween(0L, 2_000L);
            assertThat(metricsB.get("outbox.relay.leader").gauge().value()).isEqualTo(1.0);
            assertThat(meterRegistry.get("outbox.relay.leader").gauge().value()).isZero();

            // --- 3. the new leader publishes, and order still holds across the handover -----------
            writeEvents(aggregateType, run, topic, EVENTS_AFTER_HANDOVER, nextSeq);
            awaitOutboxDrained();
            assertThat(metricsB.counter("outbox.published").count()).isEqualTo(EVENTS_AFTER_HANDOVER);
            assertPerAggregateOrder(topic, run, EVENTS + EVENTS_AFTER_HANDOVER);
        } finally {
            stop.set(true);
            bLoop.get();
            ticker.shutdown();
            relayB.stepDown(); // give the lock back; A takes over again on its next tick
        }
        await("A leads again after B steps down").atMost(Duration.ofSeconds(5)).until(relayA::isLeader);
    }

    private void writeEvents(String aggregateType, String run, String topic, int count, int[] nextSeq) {
        for (int i = 0; i < count; i += 5) {
            int from = i;
            tx.executeWithoutResult(s -> {
                for (int j = from; j < from + 5; j++) {
                    int aggregate = j % AGGREGATES;
                    outboxWriter.write(aggregateType, run + "-" + aggregate, "Step", topic,
                        Map.of("run", run, "aggregate", aggregate, "seq", nextSeq[aggregate]++));
                }
            });
        }
    }

    /** Every aggregate's events, in first-delivery order, are seq 0, 1, 2, ... (duplicates allowed). */
    private void assertPerAggregateOrder(String topic, String run, int expectedEvents) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "relay-leader-check-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Set<String> seen = new LinkedHashSet<>();
        Map<Integer, List<Integer>> order = new LinkedHashMap<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (seen.size() < expectedEvents && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    String eventId = new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8);
                    if (!seen.add(eventId)) {
                        continue; // at-least-once: a redelivery doesn't change first-delivery order
                    }
                    JsonNode payload = readPayload(record.value());
                    assertThat(payload.get("run").asText()).isEqualTo(run);
                    order.computeIfAbsent(payload.get("aggregate").asInt(), k -> new ArrayList<>()).add(payload.get("seq").asInt());
                }
            }
        }
        assertThat(seen).as("distinct events delivered").hasSize(expectedEvents);
        assertThat(order).hasSize(AGGREGATES);
        order.forEach((aggregate, seqs) -> assertThat(seqs).as("aggregate %d in delivery order", aggregate).isSorted()
            .hasSize(expectedEvents / AGGREGATES).startsWith(0));
    }

    private JsonNode readPayload(String envelope) {
        try {
            return json.readTree(envelope).get("payload");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The backend holding the relay's advisory lock (bigint key: classid = high 32 bits, objid = low). */
    private long leaderPid() {
        long pid = leaderPidOrZero();
        assertThat(pid).as("a session holds the relay lock").isNotZero();
        return pid;
    }

    private long leaderPidOrZero() {
        long key = OutboxRelayLeadership.LOCK_KEY;
        return jdbcClient.sql("""
                SELECT COALESCE(max(pid), 0) FROM pg_locks
                WHERE locktype = 'advisory' AND granted AND objsubid = 1
                  AND classid::bigint = ? AND objid::bigint = ?
                """)
            .param(key >>> 32).param(key & 0xFFFFFFFFL)
            .query(Long.class).single();
    }
}
