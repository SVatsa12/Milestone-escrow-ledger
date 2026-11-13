package com.svatsa.escrow.consumers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Deduplication support for Kafka consumers.
 * Ensures that each consumer processes each event at most once, despite at-least-once delivery semantics.
 *
 * Safety model:
 * 1. INSERT INTO processed_events (consumer, event_id) ... ON CONFLICT DO NOTHING
 * 2. If 1 row inserted → first time seeing this event, run sideEffect
 * 3. If 0 rows inserted → duplicate, log and return without running sideEffect
 *
 * Why this works under concurrency:
 * - If two threads claim the same (consumer, event_id) pair concurrently:
 *   - Postgres PRIMARY KEY enforces uniqueness, so exactly one INSERT succeeds
 *   - The loser reads 0 rows and returns (duplicate detected)
 *   - Both threads' transactions commit (no exception)
 * - If a process crashes after INSERT but before sideEffect completes:
 *   - The processed_events row is already committed
 *   - On redelivery, handleOnce() detects the duplicate and skips sideEffect
 *   - No double-processing
 *
 * Note: In Kafka Consumer Listener methods, use AckMode.RECORD (manual per-message acking).
 * The listener method MUST call handleOnce() within @Transactional(propagation=REQUIRED),
 * so the INSERT and sideEffect commit together. The offset is committed AFTER the listener method
 * returns (after the transaction commits). If a crash happens in between, redelivery is harmless.
 */
@Service
public class IdempotentConsumerSupport {
    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumerSupport.class);

    private final JdbcClient jdbcClient;

    public IdempotentConsumerSupport(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Atomically claim an event for this consumer and run a side effect if this is the first time.
     *
     * @param consumerName unique consumer group name (e.g., "escrow-audit", "escrow-notifications")
     * @param eventId      time-ordered UUID of the event
     * @param sideEffect   runnable that performs the consumer's work (e.g., insert audit_log row)
     *
     * Behaviour:
     * - INSERT processed_events if not exists
     * - If inserted (1 row): run sideEffect and return normally
     * - If duplicate (0 rows inserted): log at debug level and return without running sideEffect
     * - Both paths commit the transaction (via caller's @Transactional)
     */
    @Transactional
    public void handleOnce(String consumerName, UUID eventId, Runnable sideEffect) {
        Integer inserted = jdbcClient.sql(
                "INSERT INTO processed_events (consumer, event_id) VALUES (?, ?) " +
                "ON CONFLICT DO NOTHING"
        )
            .param(consumerName)
            .param(eventId)
            .update();

        if (inserted > 0) {
            // First time processing this event for this consumer
            sideEffect.run();
        } else {
            // Duplicate: we've already processed this event
            log.debug("Duplicate event: consumer={}, eventId={}, skipping", consumerName, eventId);
        }
    }
}
