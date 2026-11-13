package com.svatsa.escrow.ledger.api;

/**
 * Request to simulate a deposit for testing/dev.
 */
public record SimulateDepositRequest(
    long clientAccountId,
    long amountPaise
) {
}
