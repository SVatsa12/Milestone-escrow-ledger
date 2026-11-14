package com.svatsa.escrow.escrow;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Repository for Project records.
 */
@Repository
public class ProjectRepository {

    private final JdbcClient jdbc;

    public ProjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Finds a project by ID.
     */
    public Optional<Project> findById(Long id) {
        return jdbc.sql("""
            SELECT id, client_id, freelancer_id, title,
                   EXTRACT(EPOCH FROM review_window)::bigint AS review_window_seconds,
                   status, escrow_account_id, dispute_hold_account_id,
                   created_at, version
            FROM projects
            WHERE id = ?
            """)
            .param(id)
            .query(ProjectRepository::mapRow)
            .optional();
    }

    /**
     * Finds all projects for a client.
     */
    public List<Project> findByClientId(Long clientId) {
        return jdbc.sql("""
            SELECT id, client_id, freelancer_id, title,
                   EXTRACT(EPOCH FROM review_window)::bigint AS review_window_seconds,
                   status, escrow_account_id, dispute_hold_account_id,
                   created_at, version
            FROM projects
            WHERE client_id = ?
            ORDER BY created_at DESC
            """)
            .param(clientId)
            .query(ProjectRepository::mapRow)
            .list();
    }

    /**
     * Saves a new project and returns it with the auto-generated ID.
     */
    public Project save(
        Long clientId,
        Long freelancerId,
        String title,
        Duration reviewWindow,
        Long escrowAccountId,
        Long disputeHoldAccountId
    ) {
        Long id = jdbc.sql("""
            INSERT INTO projects (
                client_id, freelancer_id, title, review_window,
                status, escrow_account_id, dispute_hold_account_id,
                created_at, version
            )
            VALUES (?, ?, ?, ?::interval, ?, ?, ?, now(), 0)
            RETURNING id
            """)
            .param(clientId)
            .param(freelancerId)
            .param(title)
            .param(reviewWindow.toString())
            .param("ACTIVE")
            .param(escrowAccountId)
            .param(disputeHoldAccountId)
            .query(Long.class)
            .single();

        return findById(id)
            .orElseThrow(() -> new IllegalStateException("Could not retrieve saved project"));
    }

    /**
     * Updates project status.
     */
    public void updateStatus(Long projectId, String status) {
        int rows = jdbc.sql("UPDATE projects SET status = ? WHERE id = ?")
            .param(status)
            .param(projectId)
            .update();

        if (rows == 0) {
            throw new IllegalStateException("Project not found: " + projectId);
        }
    }

    /**
     * Explicit mapping: there is no converter from Postgres INTERVAL to java.time.Duration,
     * so review_window is read as seconds.
     */
    private static Project mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Project(
            rs.getLong("id"),
            rs.getLong("client_id"),
            rs.getLong("freelancer_id"),
            rs.getString("title"),
            Duration.ofSeconds(rs.getLong("review_window_seconds")),
            rs.getString("status"),
            rs.getLong("escrow_account_id"),
            rs.getLong("dispute_hold_account_id"),
            rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
            rs.getInt("version"));
    }
}
