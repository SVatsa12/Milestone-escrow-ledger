package com.svatsa.escrow.consumers;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Audit log consumer: records all domain events from all topics.
 *
 * Subscribes to:
 * - escrow.milestone-events: project and milestone state changes
 * - escrow.payment-events: deposit and payout events
 * - ledger.entry-events: journal entries and ledger postings
 *
 * Writes one audit_log row per event, ensuring compliance and debugging trails.
 * Deduplicates using IdempotentConsumerSupport.handleOnce() so restarts don't create duplicates.
 */
@Service
public class AuditLogConsumer {
    private static final Logger log = LoggerFactory.getLogger(AuditLogConsumer.class);
    private static final String CONSUMER_NAME = "escrow-audit";

    private final IdempotentConsumerSupport dedup;
    private final AuditLogRepository auditLogRepository;

    public AuditLogConsumer(IdempotentConsumerSupport dedup, AuditLogRepository auditLogRepository) {
        this.dedup = dedup;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Listen to all three domain event topics and record each event in the audit log.
     */
    @KafkaListener(
        topics = {"escrow.milestone-events", "escrow.payment-events", "ledger.entry-events"},
        groupId = "escrow-audit"
    )
    @Transactional
    public void handleEvent(
        @Payload JsonNode payload,
        @Header("eventId") String eventIdStr,
        @Header("eventType") String eventType,
        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic
    ) {
        UUID eventId = UUID.fromString(eventIdStr);

        // Extract aggregateType and aggregateId from the payload
        String aggregateType = payload.has("aggregateType") ? payload.get("aggregateType").asText() : "UNKNOWN";
        String aggregateId = payload.has("aggregateId") ? payload.get("aggregateId").asText() : "UNKNOWN";

        dedup.handleOnce(CONSUMER_NAME, eventId, () -> {
            auditLogRepository.insert(eventId, eventType, aggregateType, aggregateId, null, payload);
            log.info("Audit logged: eventType={}, aggregateType={}, aggregateId={}, eventId={}",
                eventType, aggregateType, aggregateId, eventId);
        });
    }
}
