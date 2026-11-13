package com.svatsa.escrow.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * EventEnvelope: the standard wrapper for all domain events sent to Kafka.
 *
 * Fields:
 * - eventId: UUIDv7 (time-ordered), unique across the system. Used for deduplication.
 * - eventType: e.g. "MilestoneApproved", "DisputeOpened". Matches the topic subscription.
 * - schemaVersion: event schema version for evolving events safely (reserved for future use).
 * - aggregateType: e.g. "Milestone", "Project", "Dispute". Used for routing.
 * - aggregateId: e.g. milestone ID as string. Together with aggregateType, uniquely identifies the entity.
 * - occurredAt: when the event happened (from the domain, not system clock drift).
 * - payload: the event data as JSON. Structure depends on eventType.
 */
public record EventEnvelope(
    UUID eventId,
    String eventType,
    int schemaVersion,
    String aggregateType,
    String aggregateId,
    Instant occurredAt,
    JsonNode payload
) {
    public EventEnvelope {
        if (eventId == null) throw new IllegalArgumentException("eventId cannot be null");
        if (eventType == null || eventType.isBlank()) throw new IllegalArgumentException("eventType cannot be blank");
        if (schemaVersion < 0) throw new IllegalArgumentException("schemaVersion must be >= 0");
        if (aggregateType == null || aggregateType.isBlank()) throw new IllegalArgumentException("aggregateType cannot be blank");
        if (aggregateId == null || aggregateId.isBlank()) throw new IllegalArgumentException("aggregateId cannot be blank");
        if (occurredAt == null) throw new IllegalArgumentException("occurredAt cannot be null");
        if (payload == null) throw new IllegalArgumentException("payload cannot be null");
    }
}
