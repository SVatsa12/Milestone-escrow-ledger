package com.svatsa.escrow.chaos;

import com.svatsa.escrow.recon.ReconMismatch;
import com.svatsa.escrow.recon.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base class for the chaos scenarios (tag "chaos", run with ./mvnw verify -Pchaos).
 * All infrastructure lives in {@link AbstractToxiproxyIT}.
 *
 * Every scenario runs on its own day: the clock (shared by escrow-service and the in-process
 * payout provider) is set to 06:00 IST on a day no other scenario uses, four days apart (more
 * than reconciliation's ±1 day window). "Today" in the scenario is that day, so
 * {@link #reconcileToday()} sees only this scenario's payouts and deposits, and 0 mismatches is a
 * real check rather than one polluted by earlier scenarios in the same JVM.
 */
@Tag("chaos")
public abstract class AbstractChaosIT extends AbstractToxiproxyIT {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final AtomicInteger DAYS = new AtomicInteger();

    @Autowired protected ReconciliationService reconciliationService;

    /** The scenario's "today" (Asia/Kolkata). */
    protected LocalDate scenarioDay;

    @BeforeEach
    void ownDay() {
        scenarioDay = LocalDate.of(2041, 1, 1).plusDays(4L * DAYS.getAndIncrement());
        awaitOutboxDrained(); // a years-long jump would make rows awaiting the relay look stale (I9)
        clock.set(scenarioDay.atTime(6, 0).atZone(IST).toInstant());
    }

    /**
     * Reconcile the scenario's day with both sources (PAYOUT_PROVIDER and RAZORPAY), exactly as the
     * daily job would. Returns every mismatch, LEDGER_INVARIANT included, as "source reason entity: detail".
     */
    protected List<String> reconcileToday() {
        List<String> out = new ArrayList<>();
        for (ReconMismatch m : reconciliationService.reconcilePayouts(scenarioDay)) {
            out.add(describe(m));
        }
        for (ReconMismatch m : reconciliationService.reconcileDeposits(scenarioDay)) {
            out.add(describe(m));
        }
        return out;
    }

    private static String describe(ReconMismatch m) {
        return m.source() + " " + m.mismatchReason() + " " + m.entityId() + ": " + m.detail();
    }
}
