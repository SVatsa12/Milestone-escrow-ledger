package com.svatsa.escrow.payouts;

import java.util.EnumSet;
import java.util.Set;

/**
 * Payout state machine states.
 * 
 * States:
 * - RESERVED: freelancer wallet has been debited, payout_in_flight credited, waiting for provider call
 * - PROCESSING: provider returned 202 (async), waiting for status check
 * - UNKNOWN: provider call timed out or errored, waiting for recovery check
 * - CONFIRMED: provider confirmed success, external_bank:payouts credited
 * - FAILED: provider rejected or recovery gave up, payout_in_flight reversed to freelancer
 * - CANCELLED: idempotency tombstone or manual cancel, payout_in_flight reversed
 */
public enum PayoutState {
    RESERVED,
    PROCESSING,
    UNKNOWN,
    CONFIRMED,
    FAILED,
    CANCELLED;
    
    public boolean isTerminal() {
        return this == CONFIRMED || this == FAILED || this == CANCELLED;
    }
    
    public boolean canConfirm() {
        return this == RESERVED || this == PROCESSING || this == UNKNOWN;
    }
    
    public boolean canReverse() {
        return this == RESERVED || this == PROCESSING || this == UNKNOWN;
    }
    
    /**
     * Valid transitions from this state.
     */
    public static final java.util.Map<PayoutState, Set<PayoutState>> ALLOWED_TRANSITIONS =
        java.util.Map.ofEntries(
            java.util.Map.entry(RESERVED, EnumSet.of(PROCESSING, UNKNOWN, CONFIRMED, FAILED, CANCELLED)),
            java.util.Map.entry(PROCESSING, EnumSet.of(UNKNOWN, CONFIRMED, FAILED, CANCELLED)),
            java.util.Map.entry(UNKNOWN, EnumSet.of(CONFIRMED, FAILED, CANCELLED)),
            java.util.Map.entry(CONFIRMED, EnumSet.noneOf(PayoutState.class)),  // terminal
            java.util.Map.entry(FAILED, EnumSet.noneOf(PayoutState.class)),      // terminal
            java.util.Map.entry(CANCELLED, EnumSet.noneOf(PayoutState.class))    // terminal
        );
    
    public boolean canTransitionTo(PayoutState to) {
        Set<PayoutState> allowed = ALLOWED_TRANSITIONS.get(this);
        return allowed != null && allowed.contains(to);
    }
}
