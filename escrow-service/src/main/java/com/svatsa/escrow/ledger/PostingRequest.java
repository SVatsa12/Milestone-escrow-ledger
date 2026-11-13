package com.svatsa.escrow.ledger;

import java.util.List;

/**
 * A request to post a balanced journal entry.
 * 
 * All money movements go through LedgerService.post(PostingRequest).
 * The request carries the business context (type, reference, idempotency key) and the balanced legs.
 * 
 * @param type the journal entry type (e.g., "TRANSFER", "DEPOSIT", "RELEASE")
 * @param referenceType optional entity type being referenced (e.g., "MILESTONE", "PROJECT")
 * @param referenceId optional entity ID being referenced
 * @param idempotencyKey unique key ensuring this entry is posted at most once
 * @param description human-readable description of the entry
 * @param legs the debit/credit pairs that must sum to 0 and have >= 2 entries
 */
public record PostingRequest(
    String type,
    String referenceType,
    String referenceId,
    String idempotencyKey,
    String description,
    List<Leg> legs
) {
}
