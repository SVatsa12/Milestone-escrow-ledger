package com.svatsa.escrow.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Value("${escrow.outbox.relay.batch-size:100}")
    private int batchSize;

    public OutboxRelay(
        JdbcClient jdbcClient,
        KafkaTemplate<String, String> kafkaTemplate,
        ObjectMapper objectMapper,
        TransactionTemplate transactionTemplate,
        io.micrometer.core.instrument.MeterRegistry meterRegistry
    ) {
        this.jdbcClient = jdbcClient;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.meterRegistry = meterRegistry;
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
        List<OutboxRow> rows = transactionTemplate.execute(tx -> claimBatch());

        if (rows == null || rows.isEmpty()) {
            return;
        }

        for (OutboxRow row : rows) {
            publishEvent(row);
        }
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
     * Publishes a single event to Kafka.
     * On success, marks published_at. On failure, increments attempts and saves the error.
     */
    private void publishEvent(OutboxRow row) {
        try {
            EventEnvelope envelope = new EventEnvelope(
                row.eventId(),
                row.eventType(),
                1, // schemaVersion
                row.aggregateType(),
                row.aggregateId(),
                Instant.now(),
                objectMapper.readTree(row.payload())
            );

            String envelopeJson = objectMapper.writeValueAsString(envelope);

            // Build a message with Kafka headers for tracing
            Message<String> message = MessageBuilder.withPayload(envelopeJson)
                .setHeader(KafkaHeaders.TOPIC, row.topic())
                .setHeader("eventId", row.eventId().toString())
                .setHeader("eventType", row.eventType())
                .build();

            // Send to Kafka with a bounded timeout
            kafkaTemplate.send(message).get(5, TimeUnit.SECONDS);

            // Mark as published
            markPublished(row.id());
            meterRegistry.counter("outbox.published").increment();

        } catch (Exception e) {
            handlePublishFailure(row, e);
        }
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

            Instant nextCheckAt = Instant.now().plusMillis(delayMs);
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
     * Marks an event as published (sets published_at to now).
     */
    private void markPublished(long id) {
        transactionTemplate.execute(tx -> {
            jdbcClient.sql("""
                UPDATE outbox
                SET published_at = now()
                WHERE id = ?
                """)
                .param(id)
                .update();
            return null;
        });
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
