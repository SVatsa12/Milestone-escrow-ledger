package com.svatsa.escrow.escrow;

import java.time.Instant;

/**
 * Milestone record: unit of work within a project.
 * Holds the amount in paise (never double/float).
 * State transitions tracked via MilestoneStateMachine.
 */
public record Milestone(
    Long id,
    Long projectId,
    int seq,
    String title,
    long amountPaise,
    Instant dueAt,
    MilestoneState state,
    Instant submittedAt,
    Instant approvedAt,
    Instant releasedAt,
    Instant cancelledAt,
    int version,
    Instant createdAt
) {
    public Milestone {
        if (id == null || id <= 0) throw new IllegalArgumentException("id must be positive");
        if (projectId == null || projectId <= 0) throw new IllegalArgumentException("projectId must be positive");
        if (seq < 0) throw new IllegalArgumentException("seq cannot be negative");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title cannot be blank");
        if (amountPaise <= 0) throw new IllegalArgumentException("amountPaise must be positive");
        if (dueAt == null) throw new IllegalArgumentException("dueAt cannot be null");
        if (state == null) throw new IllegalArgumentException("state cannot be null");
        if (version < 0) throw new IllegalArgumentException("version cannot be negative");
        if (createdAt == null) throw new IllegalArgumentException("createdAt cannot be null");
    }

    /**
     * Returns true if this milestone is in a terminal state (no further transitions possible).
     */
    public boolean isTerminal() {
        return state == MilestoneState.RELEASED
            || state == MilestoneState.RESOLVED
            || state == MilestoneState.REFUNDED;
    }

    /**
     * Returns true if this milestone has disputed.
     */
    public boolean isDisputed() {
        return state == MilestoneState.DISPUTED;
    }

    /**
     * Returns true if this milestone is actively in the ledger (FUNDED, SUBMITTED, or APPROVED).
     */
    public boolean isInEscrow() {
        return state == MilestoneState.FUNDED
            || state == MilestoneState.SUBMITTED
            || state == MilestoneState.APPROVED;
    }

    /**
     * Returns true if this milestone is past its due date (checked at now).
     */
    public boolean isPastDue(Instant now) {
        return now.isAfter(dueAt);
    }
}
