package com.svatsa.escrow.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.svatsa.escrow.common.SqlTime;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * OutboxWriter: writes domain events to the outbox table.
 *
 * Every call MUST happen inside an existing @Transactional method.
 * This is enforced by propagation=MANDATORY: the method fails immediately
 * if called outside a transaction, preventing silent data loss.
 *
 * Example usage:
 *   escrowService.approveMilestone(...) {
 *       @Transactional void approveMilestone(...) {
 *           // update state, post ledger entries
 *           outboxWriter.write("Milestone", milestoneId.toString(),
 *               "MilestoneApproved", "escrow.milestone-events", approvedPayload);
 *       }
 *   }
 */
@Service
public class OutboxWriter {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxWriter(JdbcClient jdbcClient, ObjectMapper objectMapper, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Writes an event to the outbox, to be published to Kafka by OutboxRelay.
     *
     * Propagation.MANDATORY ensures this method is called inside an existing transaction.
     * If no transaction exists, a TransactionRequiredException is thrown immediately.
     *
     * @param aggregateType e.g. "Milestone", "Project", "Dispute"
     * @param aggregateId   e.g. milestone ID as string
     * @param eventType     e.g. "MilestoneApproved", "ProjectFunded"
     * @param topic         e.g. "escrow.milestone-events"
     * @param payload       the event data as a Java object (will be JSON-serialized)
     * @throws IllegalArgumentException if any parameter is invalid
     * @throws TransactionRequiredException if called outside a transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(
        String aggregateType,
        String aggregateId,
        String eventType,
        String topic,
        Object payload
    ) {
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType cannot be blank");
        }
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("aggregateId cannot be blank");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType cannot be blank");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic cannot be blank");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload cannot be null");
        }

        // created_at and the UUIDv7 timestamp both come from the injected Clock: I9 measures
        // staleness on that clock, so a test that moves time moves the outbox with it.
        Instant now = clock.instant();
        UUID eventId = UUIDv7Generator.generateAtTime(now.toEpochMilli());
        JsonNode payloadJson = objectMapper.valueToTree(payload);

        try {
            String payloadJsonStr = objectMapper.writeValueAsString(payloadJson);
            
            jdbcClient.sql("""
                INSERT INTO outbox (event_id, aggregate_type, aggregate_id, event_type, topic, payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """)
                .param(eventId)
                .param(aggregateType)
                .param(aggregateId)
                .param(eventType)
                .param(topic)
                .param(payloadJsonStr)
                .param(SqlTime.ts(now))
                .update();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize payload to JSON", e);
        }
    }
}
