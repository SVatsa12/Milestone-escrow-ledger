package com.svatsa.escrow.recon;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A single reconciliation run against external system state (payouts or deposits).
 * This is an immutable record of what was checked and what mismatches were found.
 */
public record ReconRun(
    UUID id,
    LocalDate runDate,
    String entityType,  // 'PAYOUT' or 'DEPOSIT'
    Instant startedAt,
    Instant completedAt,
    String status,  // 'RUNNING', 'COMPLETED', 'FAILED'
    int mismatchCount,
    String notes
) {
    public ReconRun withCompleted(Instant completedAt, String status, int mismatchCount) {
        return new ReconRun(id, runDate, entityType, startedAt, completedAt, status, mismatchCount, notes);
    }

    public ReconRun withNotes(String notes) {
        return new ReconRun(id, runDate, entityType, startedAt, completedAt, status, mismatchCount, notes);
    }

    public boolean isRunning() {
        return "RUNNING".equals(status);
    }

    public boolean isCompleted() {
        return "COMPLETED".equals(status);
    }

    public boolean isFailed() {
        return "FAILED".equals(status);
    }
}
