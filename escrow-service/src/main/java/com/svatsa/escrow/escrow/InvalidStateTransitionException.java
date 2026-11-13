package com.svatsa.escrow.escrow;

/**
 * Thrown when a milestone state transition is not allowed.
 * Results in HTTP 409 Conflict.
 */
public class InvalidStateTransitionException extends RuntimeException {

    private final MilestoneState from;
    private final MilestoneState to;

    public InvalidStateTransitionException(
        MilestoneState from,
        MilestoneState to,
        String message
    ) {
        super(message);
        this.from = from;
        this.to = to;
    }

    public MilestoneState getFrom() {
        return from;
    }

    public MilestoneState getTo() {
        return to;
    }

    public static InvalidStateTransitionException invalidTransition(
        MilestoneState from,
        MilestoneState to
    ) {
        return new InvalidStateTransitionException(
            from,
            to,
            "Invalid state transition from " + from + " to " + to
        );
    }
}
