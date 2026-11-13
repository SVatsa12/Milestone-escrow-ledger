package com.svatsa.escrow.ledger.api;

/**
 * Request to transfer money between two accounts.
 */
public record TransferRequest(
    long fromAccountId,
    long toAccountId,
    long amountPaise,
    String memo
) {
}
