package com.svatsa.escrow.payouts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Recovery job for payouts stuck in UNKNOWN, PROCESSING, or RESERVED states.
 * 
 * Uses lease-based claiming to avoid double-processing:
 * - Claim up to 50 payouts by updating next_check_at (short lease)
 * - Check status with provider (outside transaction)
 * - Transition based on provider response (idempotent update)
 * 
 * Runs every 10 seconds; can be disabled via config.
 */
@Component
@ConditionalOnProperty(name = "payouts.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class PayoutRecoveryJob {
    private static final Logger log = LoggerFactory.getLogger(PayoutRecoveryJob.class);
    
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final PayoutRepository payoutRepository;
    private final PayoutProviderClient providerClient;
    private final PayoutService payoutService;
    private final Clock clock;
    
    public PayoutRecoveryJob(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            PayoutRepository payoutRepository,
            PayoutProviderClient providerClient,
            PayoutService payoutService,
            Clock clock) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
        this.payoutRepository = payoutRepository;
        this.providerClient = providerClient;
        this.payoutService = payoutService;
        this.clock = clock;
    }
    
    @Scheduled(fixedDelayString = "${payouts.recovery.poll-interval:10000}")
    public void runOnce() {
        try {
            claimAndRecover();
        } catch (Exception e) {
            log.error("Recovery job error", e);
        }
    }
    
    /**
     * Claim payouts for recovery (short lease), then handle each one.
     */
    private void claimAndRecover() {
        // T1: Claim up to 50 payouts by updating next_check_at to now+60s
        // This prevents other job instances from processing the same rows
        List<UUID> claimedIds = transactionTemplate.execute(tx -> {
            Instant leaseUntil = clock.instant().plusSeconds(60);
            
            return jdbcClient.sql("""
                UPDATE payouts 
                SET next_check_at = ?, attempts = attempts + 1
                WHERE id IN (
                    SELECT id FROM payouts 
                    WHERE (state IN ('UNKNOWN', 'PROCESSING') AND next_check_at <= ?)
                       OR (state = 'RESERVED' AND updated_at < now() - INTERVAL '30 seconds')
                    ORDER BY next_check_at NULLS FIRST
                    LIMIT 50
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id
                """)
                .param(leaseUntil)
                .param(clock.instant())
                .query((rs, rowNum) -> rs.getObject(1, UUID.class))
                .stream()
                .toList();
        });
        
        if (claimedIds.isEmpty()) {
            return;
        }
        
        log.info("Recovery job claimed {} payouts", claimedIds.size());
        
        // T2+: Handle each claimed payout (outside main transaction)
        for (UUID payoutId : claimedIds) {
            try {
                handleClaimedPayout(payoutId);
            } catch (Exception e) {
                log.error("Error handling payout {} during recovery", payoutId, e);
            }
        }
    }
    
    /**
     * Handle a single claimed payout: check status at provider, transition accordingly.
     */
    private void handleClaimedPayout(UUID payoutId) {
        // Reload payout to get current state
        var payout = payoutRepository.findById(payoutId);
        if (payout.isEmpty()) {
            log.warn("Payout {} not found during recovery", payoutId);
            return;
        }
        
        Payout p = payout.get();
        
        if (p.isTerminal()) {
            log.debug("Payout {} already terminal", payoutId);
            return;
        }
        
        if (p.attempts() > 20) {
            log.error("Payout {} exceeded max attempts ({})  flagging for manual review", payoutId, p.attempts());
            return;  // Manual review needed
        }
        
        // Check status at provider
        var statusResponse = providerClient.checkStatus(payoutId);
        
        if (statusResponse == null) {
            // Still UNKNOWN (timeout/error checking status)
            // Back off with exponential delay
            long backoffSeconds = Math.min(Math.round(Math.pow(2, p.attempts()) * 10), 600);  // max 10 min
            scheduleNextCheck(payoutId, backoffSeconds);
            return;
        }
        
        if (statusResponse.statusCode() == 200) {
            // Provider has a record
            var providerPayout = statusResponse.payout();
            if (providerPayout != null && "SUCCESS".equals(providerPayout.status())) {
                payoutService.confirmPayout(p);
            } else if (providerPayout != null && "FAILED".equals(providerPayout.status())) {
                payoutService.reversePayout(p, "Provider FAILED: " + providerPayout.status());
            }
        } else if (statusResponse.statusCode() == 404) {
            // Not at provider
            if (p.createdAt().isBefore(clock.instant().minusSeconds(120))) {
                // Older than 2 minutes: send cancel/tombstone
                boolean cancelled = providerClient.cancelPayout(payoutId);
                if (cancelled) {
                    payoutService.reversePayout(p, "Tombstone cancel after 2 min");
                }
            } else {
                // Recent: back off (request may still be in flight)
                scheduleNextCheck(payoutId, 15);
            }
        }
    }
    
    /**
     * Schedule the next check for a payout.
     */
    private void scheduleNextCheck(UUID payoutId, long backoffSeconds) {
        Instant nextCheckAt = clock.instant().plusSeconds(backoffSeconds);
        payoutRepository.claimForRecovery(payoutId, nextCheckAt);
    }
}
