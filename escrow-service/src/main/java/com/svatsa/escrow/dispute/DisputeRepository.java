package com.svatsa.escrow.dispute;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Repository for Dispute records.
 */
@Repository
public class DisputeRepository {

    private final JdbcClient jdbc;

    public DisputeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Finds a dispute by ID.
     */
    public Optional<Dispute> findById(Long id) {
        return jdbc.sql("""
            SELECT id, milestone_id, opened_by, opened_by_role, reason, evidence,
                   status, freelancer_share_paise, client_share_paise, resolution_note,
                   resolved_by, journal_entry_id, opened_at, resolved_at
            FROM disputes
            WHERE id = ?
            """)
            .param(id)
            .query(Dispute.class)
            .optional();
    }

    /**
     * Finds a dispute by milestone ID (UNIQUE constraint enforces at most one).
     */
    public Optional<Dispute> findByMilestoneId(Long milestoneId) {
        return jdbc.sql("""
            SELECT id, milestone_id, opened_by, opened_by_role, reason, evidence,
                   status, freelancer_share_paise, client_share_paise, resolution_note,
                   resolved_by, journal_entry_id, opened_at, resolved_at
            FROM disputes
            WHERE milestone_id = ?
            """)
            .param(milestoneId)
            .query(Dispute.class)
            .optional();
    }

    /**
     * Saves a new dispute (opened).
     */
    public Dispute save(
        Long milestoneId,
        Long openedBy,
        String openedByRole,
        String reason,
        Object evidence
    ) {
        Long id = jdbc.sql("""
            INSERT INTO disputes (milestone_id, opened_by, opened_by_role, reason, evidence, status, opened_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, now())
            RETURNING id
            """)
            .param(milestoneId)
            .param(openedBy)
            .param(openedByRole)
            .param(reason)
            .param(evidence != null ? com.fasterxml.jackson.databind.ObjectMapper.class.getName() : "[]")
            .param("OPEN")
            .query(Long.class)
            .single();

        return findById(id)
            .orElseThrow(() -> new IllegalStateException("Could not retrieve saved dispute"));
    }

    /**
     * Resolves a dispute with the split amounts and resolution details.
     */
    public void resolve(
        Long disputeId,
        Long freelancerSharePaise,
        Long clientSharePaise,
        String resolutionNote,
        Long resolvedBy,
        Long journalEntryId
    ) {
        int rows = jdbc.sql("""
            UPDATE disputes
            SET status = ?, freelancer_share_paise = ?, client_share_paise = ?,
                resolution_note = ?, resolved_by = ?, journal_entry_id = ?, resolved_at = now()
            WHERE id = ? AND status = ?
            """)
            .param("RESOLVED")
            .param(freelancerSharePaise)
            .param(clientSharePaise)
            .param(resolutionNote)
            .param(resolvedBy)
            .param(journalEntryId)
            .param(disputeId)
            .param("OPEN")
            .update();

        if (rows == 0) {
            throw new IllegalStateException("Dispute not found or not OPEN: " + disputeId);
        }
    }
}
