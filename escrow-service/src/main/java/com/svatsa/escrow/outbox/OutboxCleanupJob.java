package com.svatsa.escrow.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * OutboxCleanupJob: deletes published events older than 7 days.
 *
 * Keeps the outbox table lean by removing old, successfully published events.
 * Events are only deleted AFTER they're published, so there's no risk of data loss.
 * The EventEnvelope's eventId is immutable and can be archived separately if needed.
 */
@Service
@ConditionalOnProperty(name = "escrow.outbox.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxCleanupJob {
    private static final Logger logger = LoggerFactory.getLogger(OutboxCleanupJob.class);

    private final JdbcClient jdbcClient;

    public OutboxCleanupJob(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Runs daily at 3 AM UTC: deletes published events older than 7 days.
     */
    @Scheduled(cron = "0 0 3 * * ?", zone = "UTC")
    public void cleanup() {
        int deleted = jdbcClient.sql("""
            DELETE FROM outbox
            WHERE published_at IS NOT NULL
              AND published_at < now() - interval '7 days'
            """)
            .update();

        if (deleted > 0) {
            logger.info("Outbox cleanup deleted {} published events older than 7 days", deleted);
        }
    }
}
