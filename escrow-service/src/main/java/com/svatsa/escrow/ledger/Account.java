package com.svatsa.escrow.ledger;

import java.time.Instant;

/**
 * An account in the ledger.
 * 
 * Accounts hold a balance which is the sum of all postings to that account.
 * The balance is denormalized for query performance and verified by invariant I3.
 * Accounts with allow_negative=false cannot go below 0 (enforced by CHECK constraint).
 * 
 * @param id unique identifier
 * @param code unique business identifier (e.g., "CLIENT_WALLET:123")
 * @param type account type (CLIENT_WALLET, ESCROW, etc.)
 * @param ownerId user ID (null for system accounts like PLATFORM_FEES)
 * @param allowNegative if true, balance can go negative (for external bank accounts)
 * @param balance current balance in paise (denormalized, verified by invariant I3)
 * @param createdAt when the account was created
 */
public record Account(
    long id,
    String code,
    AccountType type,
    Long ownerId,
    boolean allowNegative,
    long balance,
    Instant createdAt
) {
}
