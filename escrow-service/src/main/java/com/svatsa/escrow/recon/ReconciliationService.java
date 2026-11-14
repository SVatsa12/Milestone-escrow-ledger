package com.svatsa.escrow.recon;

import com.svatsa.escrow.payouts.Payout;
import com.svatsa.escrow.payouts.PayoutProviderClient;
import com.svatsa.escrow.payouts.PayoutRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reconciliation service: compares internal ledger state against external provider state.
 * Runs daily for payouts (all in RESERVED|PROCESSING|UNKNOWN|CONFIRMED states)
 * and deposits (all in CAPTURED or PENDING states).
 *
 * Design:
 * - Create a recon run record
 * - For each entity, fetch external state and compare
 * - Record mismatches (state, amount, missing entities)
 * - Mark run as completed with mismatch count
 * - Payouts in UNKNOWN state are top priority (indicate timeout/retry needed)
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationRepository reconRepo;
    private final PayoutRepository payoutRepo;
    private final PayoutProviderClient providerClient;
    private final InvariantChecker invariantChecker;

    public ReconciliationService(
        ReconciliationRepository reconRepo,
        PayoutRepository payoutRepo,
        PayoutProviderClient providerClient,
        InvariantChecker invariantChecker
    ) {
        this.reconRepo = reconRepo;
        this.payoutRepo = payoutRepo;
        this.providerClient = providerClient;
        this.invariantChecker = invariantChecker;
    }

    /**
     * Run full reconciliation for a date: check payouts and deposits.
     * This is a high-level orchestration method; each entity type runs independently.
     */
    @Transactional
    public void run(LocalDate date) {
        log.info("Starting reconciliation for date={}", date);

        // Run payout reconciliation
        try {
            reconcilePayouts(date);
        } catch (Exception e) {
            log.error("Payout reconciliation failed for date={}", date, e);
        }

        // Run deposit reconciliation (if deposit provider exists)
        try {
            reconcileDeposits(date);
        } catch (Exception e) {
            log.error("Deposit reconciliation failed for date={}", date, e);
        }

        // Run invariant check
        try {
            List<Violation> violations = invariantChecker.check();
            if (!violations.isEmpty()) {
                log.warn("Invariant violations found: {}", violations.size());
                for (Violation v : violations) {
                    log.warn("  {} - {}", v.code(), v.detail());
                }
            }
        } catch (Exception e) {
            log.error("Invariant check failed", e);
        }

        log.info("Reconciliation completed for date={}", date);
    }

    /**
     * Reconcile all payouts: compare internal state against provider.
     */
    @Transactional
    public void reconcilePayouts(LocalDate date) {
        log.info("Reconciling payouts for date={}", date);

        ReconRun run = reconRepo.getOrCreateRun(date, "PAYOUT");

        try {
            List<Payout> payouts = payoutRepo.findAllInFlightOrCompleted();
            List<ReconMismatch> mismatches = new ArrayList<>();

            for (Payout payout : payouts) {
                try {
                    ReconMismatch mismatch = checkPayout(payout, run.id());
                    if (mismatch != null) {
                        mismatches.add(mismatch);
                        reconRepo.saveMismatch(mismatch);
                        log.warn("Payout mismatch: payout={} reason={}", 
                            payout.id(), mismatch.mismatchReason());
                    }
                } catch (Exception e) {
                    log.error("Error checking payout {}", payout.id(), e);
                    ReconMismatch mismatch = new ReconMismatch(
                        UUID.randomUUID(),
                        run.id(),
                        payout.id().toString(),
                        "PAYOUT",
                        payout.state().name(),
                        null,
                        payout.amountPaise(),
                        null,
                        "CHECK_FAILED",
                        "ERROR",
                        "MANUAL_REVIEW_REQUIRED",
                        Instant.now()
                    );
                    mismatches.add(mismatch);
                    reconRepo.saveMismatch(mismatch);
                }
            }

            reconRepo.markCompleted(run.id(), mismatches.size(), null);
            log.info("Payout reconciliation complete: mismatches={}", mismatches.size());

        } catch (Exception e) {
            log.error("Payout reconciliation failed", e);
            reconRepo.markFailed(run.id(), e.getMessage());
            throw e;
        }
    }

    /**
     * Reconcile all deposits: compare internal state against provider.
     */
    @Transactional
    public void reconcileDeposits(LocalDate date) {
        log.info("Reconciling deposits for date={}", date);

        ReconRun run = reconRepo.getOrCreateRun(date, "DEPOSIT");

        try {
            // TODO: Once deposit provider integration is complete, iterate over deposits
            // and compare against provider state. For now, skip.
            reconRepo.markCompleted(run.id(), 0, "Deposits not yet reconciled");
            log.info("Deposit reconciliation skipped (provider not yet integrated)");

        } catch (Exception e) {
            log.error("Deposit reconciliation failed", e);
            reconRepo.markFailed(run.id(), e.getMessage());
            throw e;
        }
    }

    /**
     * Check a single payout against provider state.
     * Returns null if no mismatch; otherwise returns the mismatch record.
     *
     * Priority order:
     * 1. If payout is in UNKNOWN state, this is a timeout recovery — fetch from provider
     * 2. If payout is in RESERVED, provider may have moved it to PROCESSING
     * 3. If payout is in PROCESSING, check for completion (CONFIRMED, FAILED)
     * 4. If payout is in CONFIRMED, verify amount and state
     * 5. If payout is in FAILED/CANCELLED, verify state
     */
    private ReconMismatch checkPayout(Payout payout, UUID runId) {
        String internalState = payout.state().name();

        // Fetch external state from provider
        PayoutProviderClient.ProviderStatusResponse externalStatus;
        try {
            externalStatus = providerClient.checkStatus(payout.id());
        } catch (Exception e) {
            log.warn("Failed to fetch payout status from provider: payout={}", payout.id(), e);
            return new ReconMismatch(
                UUID.randomUUID(),
                runId,
                payout.id().toString(),
                "PAYOUT",
                internalState,
                "UNKNOWN",
                payout.amountPaise(),
                null,
                "PROVIDER_CHECK_FAILED",
                "ERROR",
                "RETRY_LATER",
                Instant.now()
            );
        }

        // If externalStatus is null (provider error), handle based on internal state
        if (externalStatus == null) {
            if ("UNKNOWN".equals(internalState)) {
                // Still waiting for response after timeout — this is expected
                return null;
            }
            if ("RESERVED".equals(internalState)) {
                // We reserved it but provider doesn't know — possible network lag
                return null;
            }
            // PROCESSING, CONFIRMED, FAILED, CANCELLED should exist at provider
            return new ReconMismatch(
                UUID.randomUUID(),
                runId,
                payout.id().toString(),
                "PAYOUT",
                internalState,
                "NOT_FOUND",
                payout.amountPaise(),
                null,
                "NOT_FOUND",
                "ERROR",
                "MANUAL_REVIEW_REQUIRED",
                Instant.now()
            );
        }

        // If response is 404, provider doesn't have this payout
        if (externalStatus.statusCode() == 404) {
            if ("UNKNOWN".equals(internalState)) {
                // Still trying to find it — timeout recovery in progress
                return null;
            }
            return new ReconMismatch(
                UUID.randomUUID(),
                runId,
                payout.id().toString(),
                "PAYOUT",
                internalState,
                "NOT_FOUND",
                payout.amountPaise(),
                null,
                "NOT_FOUND",
                "ERROR",
                "MANUAL_REVIEW_REQUIRED",
                Instant.now()
            );
        }

        // Provider returned a record; compare states and amounts
        var record = externalStatus.payout();
        if (record == null) {
            return null; // No record but no 404; skip
        }

        String externalState = record.status();
        // The provider speaks its own vocabulary (SUCCESS / FAILED / CANCELLED / PROCESSING).
        // Comparing it to ours as raw strings flagged every CONFIRMED payout as a mismatch.
        if (!internalState.equals(toInternalState(externalState))) {
            return new ReconMismatch(
                UUID.randomUUID(),
                runId,
                payout.id().toString(),
                "PAYOUT",
                internalState,
                externalState,
                payout.amountPaise(),
                record.amountPaise(),
                "STATE_MISMATCH",
                "ERROR",
                "MANUAL_REVIEW_REQUIRED",
                Instant.now()
            );
        }

        // Compare amounts
        if (payout.amountPaise() != record.amountPaise()) {
            return new ReconMismatch(
                UUID.randomUUID(),
                runId,
                payout.id().toString(),
                "PAYOUT",
                internalState,
                externalState,
                payout.amountPaise(),
                record.amountPaise(),
                "AMOUNT_MISMATCH",
                "ERROR",
                "MANUAL_REVIEW_REQUIRED",
                Instant.now()
            );
        }

        // No mismatch
        return null;
    }

    /**
     * Run reconciliation for a specific recon run that's currently in progress.
     * Useful for manual triggers or retries.
     */
    @Transactional
    public void runAgain(UUID runId) {
        var run = reconRepo.findRunById(runId).orElseThrow(() -> 
            new IllegalArgumentException("Recon run not found: " + runId)
        );

        if (run.isCompleted()) {
            throw new IllegalArgumentException("Cannot re-run a completed recon: " + runId);
        }

        // Re-run the appropriate reconciliation
        if ("PAYOUT".equals(run.entityType())) {
            reconcilePayouts(run.runDate());
        } else if ("DEPOSIT".equals(run.entityType())) {
            reconcileDeposits(run.runDate());
        } else {
            throw new IllegalArgumentException("Unknown entity type: " + run.entityType());
        }
    }

    /** Provider payout status -> our PayoutState name. */
    static String toInternalState(String providerStatus) {
        if (providerStatus == null) {
            return null;
        }
        return switch (providerStatus) {
            case "SUCCESS" -> "CONFIRMED";
            case "FAILED", "CANCELLED", "PROCESSING" -> providerStatus;
            default -> providerStatus;
        };
    }
}
