package com.svatsa.escrow.consumers;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository for notifications table.
 * Provides insert operations for creating user notifications.
 */
@Repository
public class NotificationRepository {
    private final JdbcClient jdbcClient;

    public NotificationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Insert a notification for a user.
     */
    public void insert(
        UUID eventId,
        long recipientUserId,
        String channel,
        String template,
        String body
    ) {
        jdbcClient.sql(
            "INSERT INTO notifications (event_id, recipient_user_id, channel, template, body) " +
            "VALUES (?, ?, ?, ?, ?)"
        )
            .param(eventId)
            .param(recipientUserId)
            .param(channel)
            .param(template)
            .param(body)
            .update();
    }
}
