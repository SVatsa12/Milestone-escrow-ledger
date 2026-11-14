package com.svatsa.escrow.consumers;

import com.svatsa.escrow.escrow.EscrowService;
import com.svatsa.escrow.escrow.Milestone;
import com.svatsa.escrow.escrow.MilestoneRepository;
import com.svatsa.escrow.escrow.Project;
import com.svatsa.escrow.escrow.User;
import com.svatsa.escrow.escrow.UserRepository;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.PostingRequest;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * P8: the audit and notification consumers, end to end through the real Kafka container.
 * (ConsumersIT only unit-tests the dedup helper and never touches Kafka; that is how the
 * notification consumer could read the wrong envelope shape without any test noticing.)
 */
@DisplayName("Kafka consumers (P8)")
class ConsumersKafkaIT extends AbstractIntegrationTest {

    private static final String TOPIC = "escrow.milestone-events";

    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private KafkaProperties kafkaProperties;
    @Autowired private EscrowService escrowService;
    @Autowired private UserRepository userRepository;
    @Autowired private MilestoneRepository milestoneRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private TransactionTemplate tx;

    @Test
    @DisplayName("the same envelope delivered 5 times → exactly 1 audit row and 1 notification")
    void duplicateDeliveriesAreProcessedOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        String envelope = milestoneSubmittedEnvelope(eventId, 4242L, 99L);
        for (int i = 0; i < 5; i++) {
            send(TOPIC, eventId, "MilestoneSubmitted", envelope);
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(count("audit_log", eventId)).isEqualTo(1);
            assertThat(count("notifications", eventId)).isEqualTo(1);
        });
        // Give the other 4 deliveries time to arrive and be ignored.
        Thread.sleep(2_000);
        assertThat(count("audit_log", eventId)).isEqualTo(1);
        assertThat(count("notifications", eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a poison message lands in .DLT and does not block the next message")
    void poisonMessageGoesToDltAndDoesNotBlock() {
        String marker = "poison-" + UUID.randomUUID();
        send(TOPIC, UUID.randomUUID(), "MilestoneSubmitted", "{ this is not json " + marker);

        UUID goodId = UUID.randomUUID();
        send(TOPIC, goodId, "MilestoneSubmitted", milestoneSubmittedEnvelope(goodId, 4243L, 98L));

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
            assertThat(count("audit_log", goodId)).as("the message after the poison one").isEqualTo(1));
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
            assertThat(dltContains(TOPIC + ".DLT", marker)).as("poison message in .DLT").isTrue());
    }

    @Test
    @DisplayName("a full project lifecycle reaches the audit log once per event, in outbox order")
    void lifecycleProducesTheExpectedAuditSequence() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        User client = userRepository.save("c-" + run, "CLIENT");
        User freelancer = userRepository.save("f-" + run, "FREELANCER");
        long wallet = accountRepository.findByCode("CLIENT_WALLET:" + client.id()).orElseThrow().id();
        long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();
        tx.executeWithoutResult(s -> ledgerService.post(new PostingRequest("DEPOSIT", "USER", client.id().toString(),
            "deposit:rzp:test-" + run, "seed", List.of(new Leg(bank, -50_000), new Leg(wallet, 50_000)))));

        Project project = escrowService.createProject(client.id(), freelancer.id(), "lifecycle-" + run,
            Duration.ofDays(5), List.of(new EscrowService.MilestoneSpec("m1", 10_000, Instant.now().plus(Duration.ofDays(30)))));
        escrowService.fundProject(project.id(), "fund-" + run);
        Milestone m = milestoneRepository.findByProjectId(project.id()).get(0);
        escrowService.submitMilestone(m.id(), "submit-" + run);
        escrowService.approveMilestone(m.id(), "approve-" + run);

        for (String[] agg : List.of(new String[]{"Project", String.valueOf(project.id())},
                                    new String[]{"Milestone", String.valueOf(m.id())})) {
            List<String> expected = jdbcClient.sql(
                    "SELECT event_type FROM outbox WHERE lower(aggregate_type) = lower(?) AND aggregate_id = ? ORDER BY id")
                .param(agg[0]).param(agg[1]).query(String.class).list();
            assertThat(expected).as("events emitted for %s %s", agg[0], agg[1]).isNotEmpty();
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(jdbcClient.sql(
                        "SELECT event_type FROM audit_log WHERE lower(aggregate_type) = lower(?) AND aggregate_id = ? ORDER BY id")
                    .param(agg[0]).param(agg[1]).query(String.class).list())
                    .as("audit rows for %s %s", agg[0], agg[1])
                    .isEqualTo(expected));
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    /** The same shape the relay publishes: an EventEnvelope whose payload uses snake_case fields. */
    private static String milestoneSubmittedEnvelope(UUID eventId, long milestoneId, long clientId) {
        return """
            {"eventId":"%s","eventType":"MilestoneSubmitted","schemaVersion":1,
             "aggregateType":"Milestone","aggregateId":"%d","occurredAt":"%s",
             "payload":{"milestone_id":%d,"project_id":1,"client_id":%d,"amount_paise":1000,
                        "review_window_days":5,"milestone_title":"t"}}
            """.formatted(eventId, milestoneId, Instant.now(), milestoneId, clientId);
    }

    private void send(String topic, UUID eventId, String eventType, String value) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, "Milestone:test", value);
        record.headers().add("eventId", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).join();
    }

    private long count(String table, UUID eventId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table + " WHERE event_id = ?")
            .param(eventId).query(Long.class).single();
    }

    private boolean dltContains(String topic, String marker) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-check-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            // Read the partitions directly: a subscribed consumer in a new group waits for the
            // broker's initial rebalance delay (~3s) before it is given anything to read.
            var partitions = consumer.partitionsFor(topic, Duration.ofSeconds(5)).stream()
                .map(p -> new org.apache.kafka.common.TopicPartition(topic, p.partition())).toList();
            if (partitions.isEmpty()) {
                return false;
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long until = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < until) {
                for (var r : consumer.poll(Duration.ofMillis(500))) {
                    if (r.value() != null && r.value().contains(marker)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
