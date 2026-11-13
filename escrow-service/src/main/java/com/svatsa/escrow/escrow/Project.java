package com.svatsa.escrow.escrow;

import java.time.Duration;
import java.time.Instant;

/**
 * Project record: container for milestones.
 * Holds ESCROW and DISPUTE_HOLD accounts, managed by the ledger.
 */
public record Project(
    Long id,
    Long clientId,
    Long freelancerId,
    String title,
    Duration reviewWindow,
    String status, // ACTIVE, COMPLETED, CANCELLED
    Long escrowAccountId,
    Long disputeHoldAccountId,
    Instant createdAt,
    int version
) {
    public Project {
        if (id == null || id <= 0) throw new IllegalArgumentException("id must be positive");
        if (clientId == null || clientId <= 0) throw new IllegalArgumentException("clientId must be positive");
        if (freelancerId == null || freelancerId <= 0) throw new IllegalArgumentException("freelancerId must be positive");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title cannot be blank");
        if (reviewWindow == null || reviewWindow.isNegative() || reviewWindow.isZero()) {
            throw new IllegalArgumentException("reviewWindow must be positive");
        }
        if (status == null || !status.matches("ACTIVE|COMPLETED|CANCELLED")) {
            throw new IllegalArgumentException("status must be ACTIVE, COMPLETED, or CANCELLED");
        }
        if (escrowAccountId == null || escrowAccountId <= 0) {
            throw new IllegalArgumentException("escrowAccountId must be positive");
        }
        if (disputeHoldAccountId == null || disputeHoldAccountId <= 0) {
            throw new IllegalArgumentException("disputeHoldAccountId must be positive");
        }
        if (createdAt == null) throw new IllegalArgumentException("createdAt cannot be null");
        if (version < 0) throw new IllegalArgumentException("version cannot be negative");
    }
}
