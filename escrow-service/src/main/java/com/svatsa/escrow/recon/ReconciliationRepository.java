package com.svatsa.escrow.recon;

import com.svatsa.escrow.common.SqlTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for reconciliation runs and mismatches.
 */
@Repository
public class ReconciliationRepository {

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public ReconciliationRepository(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    /**
     * Get or create a recon run for a given date and entity type.
     * If one already exists with RUNNING status, return it.
     * Otherwise, create a new one.
     */
    public ReconRun getOrCreateRun(LocalDate runDate, String entityType) {
        Optional<ReconRun> existing = findRun(runDate, entityType);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID id = UUID.randomUUID();
        Instant now = clock.instant();

        jdbcClient.sql("""
            INSERT INTO recon_runs (id, run_date, entity_type, started_at, status)
            VALUES (?, ?, ?, ?, 'RUNNING')
            """)
            .param(1, id)
            .param(2, runDate)
            .param(3, entityType)
            .param(4, SqlTime.ts(now))
            .update();

        return new ReconRun(id, runDate, entityType, now, null, "RUNNING", 0, null);
    }

    /**
     * Find an existing recon run.
     */
    public Optional<ReconRun> findRun(LocalDate runDate, String entityType) {
        return jdbcClient.sql("""
            SELECT id, run_date, entity_type, started_at, completed_at, status, mismatch_count, notes
            FROM recon_runs
            WHERE run_date = ? AND entity_type = ?
            ORDER BY started_at DESC
            LIMIT 1
            """)
            .param(1, runDate)
            .param(2, entityType)
            .query((rs, rowNum) -> new ReconRun(
                rs.getObject("id", UUID.class),
                rs.getObject("run_date", LocalDate.class),
                rs.getString("entity_type"),
                SqlTime.instant(rs, "started_at"),
                SqlTime.instant(rs, "completed_at"),
                rs.getString("status"),
                rs.getInt("mismatch_count"),
                rs.getString("notes")
            ))
            .optional();
    }

    /**
     * Find a recon run by ID.
     */
    public Optional<ReconRun> findRunById(UUID id) {
        return jdbcClient.sql("""
            SELECT id, run_date, entity_type, started_at, completed_at, status, mismatch_count, notes
            FROM recon_runs
            WHERE id = ?
            """)
            .param(1, id)
            .query((rs, rowNum) -> new ReconRun(
                rs.getObject("id", UUID.class),
                rs.getObject("run_date", LocalDate.class),
                rs.getString("entity_type"),
                SqlTime.instant(rs, "started_at"),
                SqlTime.instant(rs, "completed_at"),
                rs.getString("status"),
                rs.getInt("mismatch_count"),
                rs.getString("notes")
            ))
            .optional();
    }

    /**
     * Get all recon runs for a date, most recent first.
     */
    public List<ReconRun> findRunsByDate(LocalDate runDate) {
        return jdbcClient.sql("""
            SELECT id, run_date, entity_type, started_at, completed_at, status, mismatch_count, notes
            FROM recon_runs
            WHERE run_date = ?
            ORDER BY started_at DESC
            """)
            .param(1, runDate)
            .query((rs, rowNum) -> new ReconRun(
                rs.getObject("id", UUID.class),
                rs.getObject("run_date", LocalDate.class),
                rs.getString("entity_type"),
                SqlTime.instant(rs, "started_at"),
                SqlTime.instant(rs, "completed_at"),
                rs.getString("status"),
                rs.getInt("mismatch_count"),
                rs.getString("notes")
            ))
            .list();
    }

    /**
     * Mark a recon run as completed.
     */
    public void markCompleted(UUID runId, int mismatchCount, String notes) {
        jdbcClient.sql("""
            UPDATE recon_runs
            SET completed_at = ?, status = ?, mismatch_count = ?, notes = ?
            WHERE id = ?
            """)
            .param(1, SqlTime.ts(clock.instant()))
            .param(2, "COMPLETED")
            .param(3, mismatchCount)
            .param(4, notes)
            .param(5, runId)
            .update();
    }

    /**
     * Mark a recon run as failed.
     */
    public void markFailed(UUID runId, String reason) {
        jdbcClient.sql("""
            UPDATE recon_runs
            SET completed_at = ?, status = ?, notes = ?
            WHERE id = ?
            """)
            .param(1, SqlTime.ts(clock.instant()))
            .param(2, "FAILED")
            .param(3, reason)
            .param(4, runId)
            .update();
    }

    /**
     * Save a mismatch record.
     */
    public void saveMismatch(ReconMismatch mismatch) {
        jdbcClient.sql("""
            INSERT INTO recon_mismatches
            (id, recon_run_id, entity_id, entity_type, expected_state, actual_state,
             expected_amount_paise, actual_amount_paise, mismatch_reason, severity, action_taken,
             source, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (recon_run_id, entity_type, entity_id, mismatch_reason) DO NOTHING
            """)
            .param(1, mismatch.id())
            .param(2, mismatch.reconRunId())
            .param(3, mismatch.entityId())
            .param(4, mismatch.entityType())
            .param(5, mismatch.expectedState())
            .param(6, mismatch.actualState())
            .param(7, mismatch.expectedAmountPaise())
            .param(8, mismatch.actualAmountPaise())
            .param(9, mismatch.mismatchReason())
            .param(10, mismatch.severity())
            .param(11, mismatch.actionTaken())
            .param(12, mismatch.source())
            .param(13, mismatch.detail())
            .update();
    }

    /**
     * Replace every mismatch of a run with this set (one transaction, by the caller). A re-run for
     * the same date and source therefore reflects only what is wrong now: a mismatch fixed since
     * the last run disappears, and nothing is duplicated.
     */
    public void replaceMismatches(UUID runId, List<ReconMismatch> mismatches) {
        jdbcClient.sql("DELETE FROM recon_mismatches WHERE recon_run_id = ?").param(runId).update();
        mismatches.forEach(this::saveMismatch);
    }

    /**
     * Get mismatches for a recon run.
     */
    public List<ReconMismatch> findMismatchesByRun(UUID runId) {
        return jdbcClient.sql("""
            SELECT id, recon_run_id, entity_id, entity_type, expected_state, actual_state,
                   expected_amount_paise, actual_amount_paise, mismatch_reason, severity, action_taken, created_at,
                   source, detail
            FROM recon_mismatches
            WHERE recon_run_id = ?
            ORDER BY created_at DESC
            """)
            .param(1, runId)
            .query((rs, rowNum) -> new ReconMismatch(
                rs.getObject("id", UUID.class),
                rs.getObject("recon_run_id", UUID.class),
                rs.getString("entity_id"),
                rs.getString("entity_type"),
                rs.getString("expected_state"),
                rs.getString("actual_state"),
                rs.getObject("expected_amount_paise", Long.class),
                rs.getObject("actual_amount_paise", Long.class),
                rs.getString("mismatch_reason"),
                rs.getString("severity"),
                rs.getString("action_taken"),
                SqlTime.instant(rs, "created_at"),
                rs.getString("source"),
                rs.getString("detail")
            ))
            .list();
    }
}
