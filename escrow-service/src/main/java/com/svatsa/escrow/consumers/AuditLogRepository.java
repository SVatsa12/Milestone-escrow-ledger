package com.svatsa.escrow.consumers;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository for audit_log table.
 * Provides insert-only operations (audit_log is append-only).
 */
@Repository
public class AuditLogRepository {
    private final JdbcClient jdbcClient;

    public AuditLogRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Insert an audit log entry from a domain event.
     */
    public void insert(
        UUID eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        Long actorId,
        JsonNode payload
    ) {
        jdbcClient.sql(
            "INSERT INTO audit_log (event_id, event_type, aggregate_type, aggregate_id, actor_id, payload) " +
            "VALUES (?, ?, ?, ?, ?, ?::jsonb)"
        )
            .param(eventId)
            .param(eventType)
            .param(aggregateType)
            .param(aggregateId)
            .param(actorId)
            .param(payload.toString())
            .update();
    }
}
