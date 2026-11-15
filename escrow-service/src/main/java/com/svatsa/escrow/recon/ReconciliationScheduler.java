package com.svatsa.escrow.recon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Scheduled reconciliation job: every day at 20:00 UTC (01:30 IST) it reconciles the previous
 * Asia/Kolkata day. "Yesterday" comes from the injected Clock, never the server's zone.
 */
@Component
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconService;
    private final Clock clock;

    public ReconciliationScheduler(ReconciliationService reconService, Clock clock) {
        this.reconService = reconService;
        this.clock = clock;
    }

    /**
     * Reconcile the previous IST day, so the day has settled before it is checked.
     */
    @Scheduled(cron = "0 0 20 * * *", zone = "UTC")
    public void runDailyReconciliation() {
        try {
            LocalDate yesterday = LocalDate.ofInstant(clock.instant(), ReconciliationService.IST).minusDays(1);
            log.info("Starting scheduled reconciliation for date={}", yesterday);
            reconService.run(yesterday);
            log.info("Scheduled reconciliation completed for date={}", yesterday);
        } catch (Exception e) {
            log.error("Scheduled reconciliation failed", e);
            // Don't re-throw; let the next scheduled run attempt again
        }
    }

    /**
     * Alternative: Run reconciliation on manual trigger (for testing/debugging).
     * Can be invoked via a REST endpoint or admin command.
     */
    public void runManual(LocalDate date) {
        try {
            log.info("Starting manual reconciliation for date={}", date);
            reconService.run(date);
            log.info("Manual reconciliation completed for date={}", date);
        } catch (Exception e) {
            log.error("Manual reconciliation failed for date={}", date, e);
            throw e;
        }
    }
}
