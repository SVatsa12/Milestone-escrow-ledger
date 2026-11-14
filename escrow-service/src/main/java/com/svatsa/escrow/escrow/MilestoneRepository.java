package com.svatsa.escrow.escrow;

import com.svatsa.escrow.common.Actor;
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
     * Transitions a milestone to a new state and records it in milestone_transitions with the
     * actor who caused it (AGENTS.md: both approve and release are written there).
     * Conditional update: only succeeds if the milestone is still in {@code currentState} at
     * {@code currentVersion}. Returns false (and records nothing) otherwise; the caller treats
     * that as a conflict and rolls back.
     *
     * @param journalEntryId the entry that moved money for this transition, or null
     */
    public boolean transitionState(
        Long milestoneId,
        MilestoneState currentState,
        MilestoneState newState,
        int currentVersion,
        Actor actor,
        String reason,
        Long journalEntryId
    ) {
        if (!MilestoneStateMachine.isValidTransition(currentState, newState)) {
            throw InvalidStateTransitionException.invalidTransition(currentState, newState);
        }
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

        if (rows == 0) {
            return false;
        }

        jdbc.sql("""
            INSERT INTO milestone_transitions
                (milestone_id, from_state, to_state, actor_id, actor_role, reason, journal_entry_id)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """)
            .param(milestoneId)
            .param(currentState.name())
            .param(newState.name())
            .param(actor.auditId())
            .param(actor.role().name())
            .param(reason)
            .param(journalEntryId)
            .update();
        return true;
    }

    /** The audit trail of one milestone, oldest first. */
    public List<Transition> findTransitions(Long milestoneId) {
        return jdbc.sql("""
            SELECT from_state, to_state, actor_id, actor_role, reason, journal_entry_id
            FROM milestone_transitions
            WHERE milestone_id = ?
            ORDER BY id
            """)
            .param(milestoneId)
            .query((rs, n) -> new Transition(
                MilestoneState.valueOf(rs.getString("from_state")),
                MilestoneState.valueOf(rs.getString("to_state")),
                (Long) rs.getObject("actor_id", Long.class),
                rs.getString("actor_role"),
                rs.getString("reason"),
                (Long) rs.getObject("journal_entry_id", Long.class)))
            .list();
    }

    public record Transition(
        MilestoneState from,
        MilestoneState to,
        Long actorId,
        String actorRole,
        String reason,
        Long journalEntryId
    ) {}
}
