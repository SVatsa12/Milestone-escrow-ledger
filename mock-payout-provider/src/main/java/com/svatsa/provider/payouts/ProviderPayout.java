package com.svatsa.provider.payouts;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of provider.payouts, also the JSON the API returns. escrow-service reads
 * {@code status}, {@code amountPaise} and {@code beneficiary}. A cancel tombstone has no
 * beneficiary or amount.
 */
public record ProviderPayout(
    UUID id,
    String idempotencyKey,
    String beneficiary,
    Long amountPaise,
    Status status,
    String failureReason,
    Instant createdAt,
    Instant settledAt
) {
    public enum Status { PROCESSING, SUCCESS, FAILED, CANCELLED }
}
