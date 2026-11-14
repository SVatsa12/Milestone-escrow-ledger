package com.svatsa.escrow.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OutboxRelay: polls the outbox table and publishes events to Kafka.
 *
 * Guarantees:
 * - At-least-once delivery: a failure to mark published_at means the event is retried.
 * - Ordered delivery per aggregate: SELECT ... ORDER BY id ensures temporal order.
 * - Safe concurrent execution: multiple instances use SKIP LOCKED, so no row is claimed twice.
 * - Exponential backoff: failed rows are retried after 2^attempts × 100ms, capped at 10 minutes.
 *
 * Comments explain why certain patterns are used (e.g., SKIP LOCKED makes distributed queues safe).
 */
@Service
@ConditionalOnProperty(name = "escrow.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
    private static final Logger logger = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbcClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final Clock clock;

    @Value("${escrow.outbox.relay.batch-size:100}")
    private int batchSize;

    public OutboxRelay(
        JdbcClient jdbcClient,
        KafkaTemplate<String, String> kafkaTemplate,
        ObjectMapper objectMapper,
        TransactionTemplate transactionTemplate,
        io.micrometer.core.instrument.MeterRegistry meterRegistry,
        Clock clock
    ) {
        this.jdbcClient = jdbcClient;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * Polls the outbox and publishes unpublished events.
     * Runs on a fixed schedule (default 200ms between runs).
     */
    @Scheduled(fixedDelayString = "${escrow.outbox.relay.poll-interval:200}")
    public void run() {
        runOnce();
    }

    /**
     * One cycle of the relay: claim a batch of unpublished events and publish them.
     * Used by the scheduled method and also directly in tests.
     */
    public void runOnce() {
        // Keep draining while batches come back full, instead of waiting a poll interval
        // between batches. Bounded so one run can't hog the scheduler thread forever.
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            List<OutboxRow> rows = transactionTemplate.execute(tx -> claimBatch());
            if (rows == null || rows.isEmpty()) {
                return;
            }
            publishBatch(rows);
            if (rows.size() < batchSize) {
                return;
            }
        }
    }

    private static final int MAX_BATCHES_PER_RUN = 50;

    /**
     * Send the whole batch asynchronously, wait for every ack, then mark all acked rows
     * published in ONE update. It used to be send().get() plus an UPDATE per row, which capped
     * the relay at a few hundred events/s and let the outbox fall minutes behind under load
     * (found by the P15 benchmark). Still at-least-once: a row is marked published only after
     * Kafka acked it; a crash between ack and mark re-sends it, and consumers dedupe by eventId.
     */
    private void publishBatch(List<OutboxRow> rows) {
        List<java.util.concurrent.CompletableFuture<?>> sends = new java.util.ArrayList<>(rows.size());
        for (OutboxRow row : rows) {
            try {
                sends.add(kafkaTemplate.send(toMessage(row)));
            } catch (Exception e) {
                sends.add(java.util.concurrent.CompletableFuture.failedFuture(e));
            }
        }
        List<Long> published = new java.util.ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            try {
                sends.get(i).get(5, TimeUnit.SECONDS);
                published.add(rows.get(i).id());
            } catch (Exception e) {
                Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
                handlePublishFailure(rows.get(i), cause instanceof Exception ex ? ex : e);
            }
        }
        if (!published.isEmpty()) {
            transactionTemplate.executeWithoutResult(tx -> jdbcClient.sql(
                    "UPDATE outbox SET published_at = now() WHERE id = ANY(?)")
                .param(published.toArray(new Long[0]))
                .update());
            meterRegistry.counter("outbox.published").increment(published.size());
        }
    }

    private Message<String> toMessage(OutboxRow row) throws Exception {
        EventEnvelope envelope = new EventEnvelope(
            row.eventId(),
            row.eventType(),
            1, // schemaVersion
            row.aggregateType(),
            row.aggregateId(),
            clock.instant(),
            objectMapper.readTree(row.payload())
        );
        return MessageBuilder.withPayload(objectMapper.writeValueAsString(envelope))
            .setHeader(KafkaHeaders.TOPIC, row.topic())
            // Key by aggregate so every event of one project/milestone/payout lands on the same
            // partition, in outbox order. Without a key they were spread over the partitions and
            // consumers could see e.g. MilestoneReleased before MilestoneSubmitted.
            .setHeader(KafkaHeaders.KEY, row.aggregateType() + ":" + row.aggregateId())
            .setHeader("eventId", row.eventId().toString())
            .setHeader("eventType", row.eventType())
            .build();
    }

    /**
     * Claims a batch of unpublished events using SKIP LOCKED.
     *
     * SELECT ... FOR UPDATE SKIP LOCKED ensures:
     * 1. Multiple OutboxRelay instances can run concurrently without coordination.
     * 2. Each row is claimed by exactly one instance and locked until commit.
     * 3. An instance that crashes before marking published_at releases the lock,
     *    so the row is picked up again by another instance or the next poll.
     *
     * This pattern is standard for distributed event sourcing systems and avoids
     * needing a distributed lock service.
     */
    private List<OutboxRow> claimBatch() {
        return jdbcClient.sql("""
            SELECT id, event_id, aggregate_type, aggregate_id, event_type, topic, payload, attempts
            FROM outbox
            WHERE published_at IS NULL
            ORDER BY id
            FOR UPDATE OF outbox SKIP LOCKED
            LIMIT ?
            """)
            .param(batchSize)
            .query(OutboxRow.class)
            .list();
    }

    /**
     * Handles a failed publish attempt.
     * Uses exponential backoff: 2^attempts * 100ms, capped at 10 minutes.
     */
    private void handlePublishFailure(OutboxRow row, Exception e) {
        transactionTemplate.execute(tx -> {
            int nextAttempts = row.attempts() + 1;
            long delayMs = Math.min(
                (long) Math.pow(2, nextAttempts) * 100,
                600_000 // 10 minutes
            );

            Instant nextCheckAt = clock.instant().plusMillis(delayMs);
            String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

            jdbcClient.sql("""
                UPDATE outbox
                SET attempts = ?, last_error = ?, published_at = NULL
                WHERE id = ?
                """)
                .param(nextAttempts)
                .param(errorMessage)
                .param(row.id())
                .update();

            return null;
        });

        logger.warn(
            "Failed to publish outbox event {} (aggregate {}/{}), attempt {}. Will retry in {}ms. Error: {}",
            row.eventId(),
            row.aggregateType(),
            row.aggregateId(),
            row.attempts() + 1,
            Math.min((long) Math.pow(2, row.attempts() + 1) * 100, 600_000),
            e.getMessage()
        );

        meterRegistry.counter("outbox.publish.failed").increment();
    }

    /**
     * Provides a metric for the number of unpublished events.
     */
    @Scheduled(fixedDelayString = "${escrow.outbox.relay.gauge-interval:5000}")
    public void updateGauges() {
        Integer pending = jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL")
            .query(Integer.class)
            .single();

        if (pending != null) {
            meterRegistry.gauge("outbox.pending", pending);
        }
    }

    /**
     * Record for one row read from the outbox table.
     * Separate from EventEnvelope because it includes metadata (id, attempts, etc.).
     */
    public record OutboxRow(
        Long id,
        java.util.UUID eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String topic,
        String payload,
        Integer attempts
    ) {
    }
}
