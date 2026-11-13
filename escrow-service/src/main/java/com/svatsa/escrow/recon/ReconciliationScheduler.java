package com.svatsa.escrow.recon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Scheduled reconciliation job.
 * Runs daily at 02:30 IST (previous day's data).
 *
 * Cron expression: 0 30 2 * * *
 *   - 0: second (0)
 *   - 30: minute (30)
 *   - 2: hour (2, in UTC; IST is UTC+5:30, so 2 UTC = 7:30 IST)
 *
 * NOTE: The cron is expressed in UTC. To run at 02:30 IST, we need to convert:
 *   02:30 IST = 20:00 UTC (previous day)
 *   So the cron should be: 0 0 20 * * * (08:00 PM UTC)
 *
 * However, Spring's @Scheduled uses the server's local timezone unless configured.
 * For production, ensure the application runs in UTC or configure zone explicitly.
 * For now, using UTC cron: 0 0 20 * * * (which is 02:30 IST if server is in IST).
 */
@Component
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconService;

    public ReconciliationScheduler(ReconciliationService reconService) {
        this.reconService = reconService;
    }

    /**
     * Run reconciliation daily at 02:30 IST.
     * The previous day is reconciled (to allow for time zone variance and
     * give the day time to settle before checking).
     *
     * Cron: 0 0 20 * * * (8 PM UTC = 2:30 AM IST next day)
     */
    @Scheduled(cron = "0 0 20 * * *", zone = "UTC")
    public void runDailyReconciliation() {
        try {
            LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1);
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
