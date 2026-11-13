package com.svatsa.escrow.ledger;

/**
 * Result of a posting operation.
 * 
 * @param entryId the journal entry ID (either newly created or replayed)
 * @param replayed true if this entry was already posted under the same idempotency key (request was retried)
 */
public record PostingResult(long entryId, boolean replayed) {
}
