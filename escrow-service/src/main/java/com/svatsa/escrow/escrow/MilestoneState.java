package com.svatsa.escrow.escrow;

/**
 * Milestone state machine states.
 *
 * DRAFT        → not yet funded
 * FUNDED       → client has paid, freelancer hasn't submitted
 * SUBMITTED    → freelancer submitted work, client reviewing
 * APPROVED     → client approved, transitioning to RELEASED
 * RELEASED     → client approved and money released to freelancer
 * DISPUTED     → dispute opened while SUBMITTED
 * RESOLVED     → dispute resolved, freelancer gets split
 * CANCELLED    → client cancelled (only within grace period after due date)
 * REFUNDED     → refund issued back to client
 */
public enum MilestoneState {
    DRAFT,
    FUNDED,
    SUBMITTED,
    APPROVED,
    RELEASED,
    DISPUTED,
    RESOLVED,
    CANCELLED,
    REFUNDED
}
