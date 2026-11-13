package com.svatsa.escrow.ledger.api;

import com.svatsa.escrow.ledger.AccountType;

/**
 * Request to create a new account.
 */
public record CreateAccountRequest(
    AccountType type,
    Long ownerId
) {
}
