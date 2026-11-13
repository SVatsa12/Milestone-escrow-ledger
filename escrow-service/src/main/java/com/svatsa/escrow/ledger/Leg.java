package com.svatsa.escrow.ledger;

/**
 * A single leg of a journal entry (debit or credit).
 * 
 * In double-entry bookkeeping, every transaction has at least 2 legs that sum to zero.
 * A leg specifies an account and an amount:
 * - positive amount = credit (increase account balance)
 * - negative amount = debit (decrease account balance)
 * 
 * The canonical constructor rejects amount == 0, since a zero leg is meaningless.
 * 
 * @param accountId the account being debited or credited
 * @param amountPaise the amount in paise: positive for credit, negative for debit
 */
public record Leg(long accountId, long amountPaise) {
    
    /**
     * Canonical constructor: reject zero amounts since they add no value.
     */
    public Leg {
        if (amountPaise == 0) {
            throw new IllegalArgumentException("Leg amount cannot be zero");
        }
    }
}
