package com.svatsa.escrow.escrow;

import com.svatsa.escrow.common.SqlTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Repository for Milestone records.
 */
@Repository
public class MilestoneRepository {

    private final JdbcClient jdbc;

    public MilestoneRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Finds a milestone by ID.
     */
    public Optional<Milestone> findById(Long id) {
        return jdbc.sql("""
            SELECT id, project_id, seq, title, amount_paise, due_at, state,
                   submitted_at, approved_at, released_at, cancelled_at,
                   version, created_at
            FROM milestones
            WHERE id = ?
            """)
            .param(id)
            .query(Milestone.class)
            .optional();
    }

    /**
     * Finds all milestones for a project, ordered by sequence.
     */
    public List<Milestone> findByProjectId(Long projectId) {
        return jdbc.sql("""
            SELECT id, project_id, seq, title, amount_paise, due_at, state,
                   submitted_at, approved_at, released_at, cancelled_at,
                   version, created_at
            FROM milestones
            WHERE project_id = ?
            ORDER BY seq ASC
            """)
            .param(projectId)
            .query(Milestone.class)
            .list();
    }

    /**
     * Finds milestones in a given state.
     */
    public List<Milestone> findByState(MilestoneState state) {
        return jdbc.sql("""
            SELECT id, project_id, seq, title, amount_paise, due_at, state,
                   submitted_at, approved_at, released_at, cancelled_at,
                   version, created_at
            FROM milestones
            WHERE state = ?
            ORDER BY created_at DESC
            """)
            .param(state.name())
            .query(Milestone.class)
            .list();
    }

    /**
     * Saves a new milestone and returns it with the auto-generated ID.
     */
    public Milestone save(
        Long projectId,
        int seq,
        String title,
        long amountPaise,
        Instant dueAt
    ) {
        Long id = jdbc.sql("""
            INSERT INTO milestones (
                project_id, seq, title, amount_paise, due_at, state,
                version, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, 0, now())
            RETURNING id
            """)
            .param(projectId)
            .param(seq)
            .param(title)
            .param(amountPaise)
            .param(SqlTime.ts(dueAt))
            .param(MilestoneState.DRAFT.name())
            .query(Long.class)
            .single();

        return findById(id)
            .orElseThrow(() -> new IllegalStateException("Could not retrieve saved milestone"));
    }

    /**
     * Transitions a milestone to a new state.
     * Uses optimistic locking: only succeeds if version matches.
     * Returns true if successful, false if version mismatch.
     */
    public boolean transitionState(
        Long milestoneId,
        MilestoneState currentState,
        MilestoneState newState,
        int currentVersion
    ) {
        String stateField = switch (newState) {
            case SUBMITTED -> "submitted_at";
            case APPROVED -> "approved_at";
            case RELEASED -> "released_at";
            case CANCELLED -> "cancelled_at";
            default -> null;
        };

        String sql;
        if (stateField != null) {
            sql = String.format(
                """
                UPDATE milestones
                SET state = ?, version = version + 1, %s = now()
                WHERE id = ? AND state = ? AND version = ?
                """,
                stateField
            );
        } else {
            sql = """
                UPDATE milestones
                SET state = ?, version = version + 1
                WHERE id = ? AND state = ? AND version = ?
                """;
        }

        int rows = jdbc.sql(sql)
            .param(newState.name())
            .param(milestoneId)
            .param(currentState.name())
            .param(currentVersion)
            .update();

        return rows > 0;
    }
}
