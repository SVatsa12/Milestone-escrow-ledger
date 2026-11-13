package com.svatsa.escrow.escrow;

import java.time.Instant;

/**
 * Thrown when a client tries to cancel a FUNDED milestone before dueAt + grace period.
 * Results in HTTP 409 Conflict.
 */
public class CancelTooEarlyException extends RuntimeException {

    private final Instant dueAt;
    private final Instant canCancelAt;
    private final Instant now;

    public CancelTooEarlyException(Instant dueAt, Instant canCancelAt, Instant now) {
        super(
            "Cannot cancel before " + canCancelAt +
            " (due: " + dueAt +
            ", grace period passed, now: " + now + ")"
        );
        this.dueAt = dueAt;
        this.canCancelAt = canCancelAt;
        this.now = now;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public Instant getCanCancelAt() {
        return canCancelAt;
    }

    public Instant getNow() {
        return now;
    }
}
