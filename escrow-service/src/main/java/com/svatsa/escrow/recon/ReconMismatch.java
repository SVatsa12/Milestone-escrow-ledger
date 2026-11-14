package com.svatsa.escrow.recon;

import java.time.Instant;
import java.util.UUID;

/**
 * A single mismatch discovered during reconciliation.
 * Immutable record of expected vs. actual state from the provider.
 */
public record ReconMismatch(
    UUID id,
    UUID reconRunId,
    String entityId,  // payout UUID or deposit ID
    String entityType,  // 'PAYOUT' or 'DEPOSIT'
    String expectedState,
    String actualState,
    Long expectedAmountPaise,
    Long actualAmountPaise,
    String mismatchReason,  // 'STATE_MISMATCH', 'AMOUNT_MISMATCH', 'NOT_FOUND', etc.
    String severity,  // 'ERROR' or 'WARN'
    String actionTaken,  // what we did about it
    Instant createdAt,
    String source,       // PAYOUT_PROVIDER, RAZORPAY, LEDGER (null on rows written before V12)
    String detail        // human-readable context, e.g. our current state for MISSING_IN_LEDGER
) {
    /** Without source and detail (the pre-V12 shape). */
    public ReconMismatch(UUID id, UUID reconRunId, String entityId, String entityType, String expectedState,
                         String actualState, Long expectedAmountPaise, Long actualAmountPaise, String mismatchReason,
                         String severity, String actionTaken, Instant createdAt) {
        this(id, reconRunId, entityId, entityType, expectedState, actualState, expectedAmountPaise, actualAmountPaise,
            mismatchReason, severity, actionTaken, createdAt, null, null);
    }

    public static String reason(String desc) {
        return desc;
    }

    public boolean isError() {
        return "ERROR".equals(severity);
    }

    public boolean isWarning() {
        return "WARN".equals(severity);
    }

    public boolean isPayout() {
        return "PAYOUT".equals(entityType);
    }

    public boolean isDeposit() {
        return "DEPOSIT".equals(entityType);
    }
}
