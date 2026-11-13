package com.svatsa.escrow.recon;

/**
 * A single invariant violation found during reconciliation.
 * 
 * @param code the invariant code (e.g., "I1", "I3")
 * @param detail human-readable description of the violation
 */
public record Violation(String code, String detail) {
}
