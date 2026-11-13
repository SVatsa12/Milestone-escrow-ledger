package com.svatsa.escrow.dispute;

import java.time.Instant;
import java.util.List;

/**
 * Dispute record: represents a conflict over a milestone payment.
 * Disputes freeze the milestone's escrow funds until resolved by an admin.
 */
public record Dispute(
    Long id,
    Long milestoneId,
    Long openedBy,
    String openedByRole, // CLIENT or FREELANCER
    String reason,
    List<Object> evidence,
    String status, // OPEN or RESOLVED
    Long freelancerSharePaise,
    Long clientSharePaise,
    String resolutionNote,
    Long resolvedBy,
    Long journalEntryId,
    Instant openedAt,
    Instant resolvedAt
) {
    public Dispute {
        if (id == null || id <= 0) throw new IllegalArgumentException("id must be positive");
        if (milestoneId == null || milestoneId <= 0) throw new IllegalArgumentException("milestoneId must be positive");
        if (openedBy == null || openedBy <= 0) throw new IllegalArgumentException("openedBy must be positive");
        if (openedByRole == null || !openedByRole.matches("CLIENT|FREELANCER")) {
            throw new IllegalArgumentException("openedByRole must be CLIENT or FREELANCER");
        }
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason cannot be blank");
        if (status == null || !status.matches("OPEN|RESOLVED")) {
            throw new IllegalArgumentException("status must be OPEN or RESOLVED");
        }
        if (openedAt == null) throw new IllegalArgumentException("openedAt cannot be null");
    }

    /**
     * Returns true if this dispute is resolved.
     */
    public boolean isResolved() {
        return "RESOLVED".equals(status);
    }

    /**
     * Returns true if this dispute is still open.
     */
    public boolean isOpen() {
        return "OPEN".equals(status);
    }
}
