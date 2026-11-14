package com.svatsa.escrow.outbox;

import com.svatsa.escrow.escrow.EscrowService;
import com.svatsa.escrow.escrow.InvalidStateTransitionException;
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
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * P7: the transactional outbox and its relay, against the real Kafka container.
 */
@DisplayName("Outbox and relay (P7)")
class OutboxIT extends AbstractIntegrationTest {

    @Autowired private EscrowService escrowService;
    @Autowired private UserRepository userRepository;
    @Autowired private MilestoneRepository milestoneRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private TransactionTemplate tx;
    @Autowired private KafkaProperties kafkaProperties;

    @Test
    @DisplayName("approving a milestone publishes MilestoneApproved, MilestoneReleased and LedgerEntryPosted")
    void approveEventsReachKafka() {
        Milestone m = submittedMilestone();
        escrowService.approveMilestone(m.id(), "approve-" + m.id());

        List<String> eventIds = jdbcClient.sql("""
                SELECT event_id::text FROM outbox
                WHERE (aggregate_type = 'Milestone' AND aggregate_id = ? AND event_type IN ('MilestoneApproved','MilestoneReleased'))
                   OR (event_type = 'LedgerEntryPosted' AND payload->>'reference_type' = 'MILESTONE' AND payload->>'reference_id' = ?)
                """)
            .param(String.valueOf(m.id())).param(String.valueOf(m.id())).query(String.class).list();
        assertThat(eventIds).as("approve wrote its three events to the outbox").hasSize(3);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            List<String> onKafka = readAll(List.of("escrow.milestone-events", "ledger.entry-events"));
            assertThat(eventIds).allSatisfy(id -> assertThat(onKafka).anySatisfy(v -> assertThat(v).contains(id)));
        });
    }

    @Test
    @DisplayName("a business exception rolls back: no outbox row")
    void rolledBackOperationWritesNoOutboxRow() {
        Milestone m = submittedMilestone();
        long before = outboxRowsFor(m);
        escrowService.approveMilestone(m.id(), "approve-" + m.id());
        long afterApprove = outboxRowsFor(m);

        // The milestone is RELEASED now; approving again is a state conflict and rolls back.
        assertThatThrownBy(() -> escrowService.approveMilestone(m.id(), "approve-again-" + m.id()))
            .isInstanceOf(InvalidStateTransitionException.class);

        assertThat(afterApprove).isGreaterThan(before);
        assertThat(outboxRowsFor(m)).as("the failed approve left nothing behind").isEqualTo(afterApprove);
    }

    @Test
    @DisplayName("Kafka down: operations still succeed, the outbox holds them, and it drains when Kafka is back")
    void kafkaOutageIsAbsorbedByTheOutbox() {
        List<Long> milestones = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            milestones.add(submittedMilestone().id());
        }
        pauseKafka();
        try {
            for (long id : milestones) {
                escrowService.approveMilestone(id, "approve-" + id);   // must not fail
            }
            long pending = jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL")
                .query(Long.class).single();
            assertThat(pending).as("events waiting for Kafka").isGreaterThanOrEqualTo(10);
        } finally {
            unpauseKafka();
        }

        await().atMost(Duration.ofMinutes(3)).untilAsserted(() ->
            assertThat(jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL")
                .query(Long.class).single()).isZero());
    }

    // --- helpers -----------------------------------------------------------------------------

    private Milestone submittedMilestone() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        User client = userRepository.save("c-" + run, "CLIENT");
        User freelancer = userRepository.save("f-" + run, "FREELANCER");
        long wallet = accountRepository.findByCode("CLIENT_WALLET:" + client.id()).orElseThrow().id();
        long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();
        tx.executeWithoutResult(s -> ledgerService.post(new PostingRequest("DEPOSIT", "USER", client.id().toString(),
            "deposit:rzp:test-" + run, "seed", List.of(new Leg(bank, -10_000), new Leg(wallet, 10_000)))));
        Project project = escrowService.createProject(client.id(), freelancer.id(), "outbox-" + run,
            Duration.ofDays(5), List.of(new EscrowService.MilestoneSpec("m1", 5_000, Instant.now().plus(Duration.ofDays(30)))));
        escrowService.fundProject(project.id(), "fund-" + run);
        Milestone m = milestoneRepository.findByProjectId(project.id()).get(0);
        escrowService.submitMilestone(m.id(), "submit-" + run);
        return m;
    }

    private long outboxRowsFor(Milestone m) {
        return jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE aggregate_type = 'Milestone' AND aggregate_id = ?")
            .param(String.valueOf(m.id())).query(Long.class).single();
    }

    private List<String> readAll(List<String> topics) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> values = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (String topic : topics) {
                consumer.partitionsFor(topic, Duration.ofSeconds(5))
                    .forEach(p -> partitions.add(new TopicPartition(topic, p.partition())));
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            long until = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < until
                    && partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> values.add(r.value()));
            }
        }
        return values;
    }
}
