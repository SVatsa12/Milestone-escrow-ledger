package com.svatsa.escrow.ledger;

import java.util.UUID;

/**
 * Journal entry types and business idempotency keys for every money flow (AGENTS.md "Money
 * flows" table, invariant 8). Each business action has exactly one key, so the ledger posts it at
 * most once no matter which HTTP request, retry, job or race triggers it. The HTTP
 * Idempotency-Key is only for replaying API responses and never reaches the ledger.
 *
 * InvariantChecker I10 checks every entry of these types against these patterns.
 */
public final class LedgerKeys {

    // Entry types (journal_entries.type) and the reference each one points at.
    public static final String FUND = "FUND";                       // PROJECT
    public static final String RELEASE = "RELEASE";                 // MILESTONE
    public static final String DISPUTE_FREEZE = "DISPUTE_FREEZE";   // MILESTONE
    public static final String DISPUTE_RESOLVE = "DISPUTE_RESOLVE"; // DISPUTE
    public static final String REFUND = "REFUND";                   // MILESTONE
    public static final String DEPOSIT = "DEPOSIT";                 // DEPOSIT (key uses the Razorpay payment id)
    public static final String PAYOUT_RESERVE = "PAYOUT_RESERVE";   // PAYOUT
    public static final String PAYOUT_CONFIRM = "PAYOUT_CONFIRM";   // PAYOUT
    public static final String PAYOUT_REVERSE = "PAYOUT_REVERSE";   // PAYOUT
    public static final String PAYOUT_CANCEL = "PAYOUT_CANCEL";     // PAYOUT (a reverse ending in CANCELLED)

    private LedgerKeys() {
    }

    public static String fund(long projectId) {
        return "fund:project:" + projectId;
    }

    public static String release(long milestoneId) {
        return "release:milestone:" + milestoneId;
    }

    public static String disputeFreeze(long milestoneId) {
        return "dispute-freeze:milestone:" + milestoneId;
    }

    public static String disputeResolve(long disputeId) {
        return "dispute-resolve:" + disputeId;
    }

    public static String refund(long milestoneId) {
        return "refund:milestone:" + milestoneId;
    }

    public static String deposit(String razorpayPaymentId) {
        return "deposit:rzp:" + razorpayPaymentId;
    }

    public static String payoutReserve(UUID payoutId) {
        return "payout:reserve:" + payoutId;
    }

    public static String payoutConfirm(UUID payoutId) {
        return "payout:confirm:" + payoutId;
    }

    /** Both PAYOUT_REVERSE and PAYOUT_CANCEL: a payout is reversed at most once, whatever the reason. */
    public static String payoutReverse(UUID payoutId) {
        return "payout:reverse:" + payoutId;
    }
}
