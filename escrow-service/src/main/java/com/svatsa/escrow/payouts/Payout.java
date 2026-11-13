package com.svatsa.escrow.payouts;

import java.time.Instant;
import java.util.UUID;

/**
 * Payout record: a payment from escrow to a freelancer's bank account.
 * 
 * Invariant: PAYOUT_IN_FLIGHT account balance = SUM(amount_paise) of all payouts in
 * (RESERVED, PROCESSING, UNKNOWN). When CONFIRMED, the funds are moved to EXTERNAL_BANK:PAYOUTS.
 * When FAILED or CANCELLED, they're reversed back to FREELANCER_WALLET.
 */
public record Payout(
    UUID id,
    long freelancerId,
    long walletAccountId,
    long amountPaise,
    PayoutState state,
    String providerStatus,
    int attempts,
    Instant nextCheckAt,
    String lastError,
    Instant createdAt,
    Instant updatedAt,
    int version
) {
    
    public Payout {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("amountPaise must be > 0");
        }
    }
    
    public boolean isTerminal() {
        return state.isTerminal();
    }
    
    public boolean canConfirm() {
        return state.canConfirm();
    }
    
    public boolean canReverse() {
        return state.canReverse();
    }
}
