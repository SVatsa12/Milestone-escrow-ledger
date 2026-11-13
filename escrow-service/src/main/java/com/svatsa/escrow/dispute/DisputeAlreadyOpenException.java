package com.svatsa.escrow.dispute;

/**
 * Thrown when attempting to open a dispute on a milestone that already has an open dispute.
 */
public class DisputeAlreadyOpenException extends RuntimeException {

    public DisputeAlreadyOpenException(Long milestoneId) {
        super("A dispute is already open for milestone " + milestoneId);
    }

    public DisputeAlreadyOpenException(String message) {
        super(message);
    }
}
