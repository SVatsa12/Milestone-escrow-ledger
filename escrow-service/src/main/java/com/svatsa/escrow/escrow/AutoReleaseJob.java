package com.svatsa.escrow.escrow;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Auto-release job: releases milestones when the client review window expires.
 *
 * Runs frequently (default 30s interval). Each run processes up to 500 milestones
 * in SUBMITTED state where submitted_at + project.review_window <= now.
 *
 * Design:
 * - Each iteration is one short transaction (claim + release).
 * - Uses SELECT ... FOR UPDATE SKIP LOCKED to claim one milestone atomically.
 * - SKIP LOCKED ensures multiple instances can run safely: each instance
 *   skips already-locked rows and claims the next available one.
 * - Claiming and releasing in the same transaction holds the lock until
 *   money has moved, preventing other instances from racing.
 * - If one milestone fails, we log it and keep processing the rest.
 *
 * Metrics:
 * - escrow.auto_release.released (counter)
 * - escrow.auto_release.failed (counter)
 */
@Service
@ConditionalOnProperty(
    name = "escrow.auto-release.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class AutoReleaseJob {

    private static final Logger log = LoggerFactory.getLogger(AutoReleaseJob.class);

    private final JdbcClient jdbc;
    private final EscrowService escrowService;
    private final MilestoneRepository milestoneRepository;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    public AutoReleaseJob(
        JdbcClient jdbc,
        EscrowService escrowService,
        MilestoneRepository milestoneRepository,
        Clock clock,
        TransactionTemplate transactionTemplate,
        MeterRegistry meterRegistry
    ) {
        this.jdbc = jdbc;
        this.escrowService = escrowService;
        this.milestoneRepository = milestoneRepository;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Scheduled job: runs every 30 seconds by default (configurable).
     */
    @Scheduled(fixedDelayString = "${escrow.auto-release.poll-interval:30000}")
    public void run() {
        log.debug("Auto-release job starting");
        runOnce();
    }

    /**
     * Process up to 500 auto-releasable milestones.
     * Called from the scheduler or directly in tests.
     */
    public void runOnce() {
        int processed = 0;
        Instant now = clock.instant();

        for (int iteration = 0; iteration < 500; iteration++) {
            // Claim one milestone in a transaction
            Optional<Long> milestoneId = claimOneAndRelease(now);

            if (milestoneId.isEmpty()) {
                log.debug("Auto-release: no more milestones to process");
                break;
            }

            processed++;
        }

        if (processed > 0) {
            log.info("Auto-release job completed: {} milestones released", processed);
        }
    }

    /**
     * In a single transaction:
     * 1. Claim one SUBMITTED milestone that's past the review window using SKIP LOCKED.
     * 2. Release it (same as manual approval).
     * Returns Optional.of(milestoneId) if released, Optional.empty() if none available.
     */
    private Optional<Long> claimOneAndRelease(Instant now) {
        return transactionTemplate.execute(status -> {
            // Claim and lock one milestone
            Long milestoneId = jdbc.sql("""
                SELECT m.id
                FROM milestones m
                JOIN projects p ON p.id = m.project_id
                WHERE m.state = ? AND m.submitted_at + p.review_window <= ?
                ORDER BY m.submitted_at ASC
                FOR UPDATE OF m
                SKIP LOCKED
                LIMIT 1
                """)
                .param(MilestoneState.SUBMITTED.name())
                .param(now)
                .query(Long.class)
                .optional()
                .orElse(null);

            if (milestoneId == null) {
                return Optional.empty();
            }

            try {
                // Release it using the same key as manual approval
                // The idempotency key ensures only one entry if retried
                String idempotencyKey = "release:milestone:" + milestoneId;
                escrowService.approveMilestone(milestoneId, idempotencyKey);

                log.debug("Auto-released milestone {}", milestoneId);
                meterRegistry.counter("escrow.auto_release.released").increment();

                return Optional.of(milestoneId);
            } catch (Exception e) {
                log.error("Failed to auto-release milestone {}: {}", milestoneId, e.getMessage(), e);
                meterRegistry.counter("escrow.auto_release.failed").increment();

                // Log and continue: don't let one failure block the whole job
                return Optional.of(milestoneId); // Count as processed even though it failed
            }
        });
    }
}
