package com.svatsa.escrow.payouts;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for payout operations.
 * Uses JdbcClient for raw SQL (no JPA in the money path per AGENTS.md).
 */
@Repository
public class PayoutRepository {
    
    private final JdbcClient jdbcClient;
    
    public PayoutRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }
    
    /**
     * Create a new payout in RESERVED state.
     */
    public Payout createReserved(UUID id, long freelancerId, long walletAccountId, long amountPaise) {
        Instant now = Instant.now();
        jdbcClient.sql("""
            INSERT INTO payouts (id, freelancer_id, wallet_account_id, amount_paise, state, 
                                 created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            """)
            .param(id)
            .param(freelancerId)
            .param(walletAccountId)
            .param(amountPaise)
            .param(PayoutState.RESERVED.name())
            .param(now)
            .param(now)
            .update();
        
        return new Payout(id, freelancerId, walletAccountId, amountPaise, PayoutState.RESERVED, 
                         null, 0, null, null, now, now, 0);
    }
    
    /**
     * Find a payout by ID.
     */
    public Optional<Payout> findById(UUID id) {
        return jdbcClient.sql("""
            SELECT id, freelancer_id, wallet_account_id, amount_paise, state, provider_status,
                   attempts, next_check_at, last_error, created_at, updated_at, version
            FROM payouts WHERE id = ?
            """)
            .param(id)
            .query(this::mapRow)
            .optional();
    }
    
    /**
     * Find all payouts in flight or completed states (for reconciliation).
     * States: RESERVED, PROCESSING, UNKNOWN, CONFIRMED, FAILED, CANCELLED.
     */
    public List<Payout> findAllInFlightOrCompleted() {
        return jdbcClient.sql("""
            SELECT id, freelancer_id, wallet_account_id, amount_paise, state, provider_status,
                   attempts, next_check_at, last_error, created_at, updated_at, version
            FROM payouts 
            WHERE state IN ('RESERVED', 'PROCESSING', 'UNKNOWN', 'CONFIRMED', 'FAILED', 'CANCELLED')
            ORDER BY created_at DESC
            """)
            .query(this::mapRow)
            .list();
    }
    
    /**
     * Transition a payout to a new state (conditional update).
     * Returns the number of rows updated (0 if the transition was blocked).
     */
    public int transitionState(UUID id, PayoutState fromState, PayoutState toState, String reason) {
        Instant now = Instant.now();
        int updated = jdbcClient.sql("""
            UPDATE payouts 
            SET state = ?, version = version + 1, updated_at = ?, next_check_at = ?
            WHERE id = ? AND state = ?
            """)
            .param(toState.name())
            .param(now)
            .param(null)  // clear next_check_at on state change
            .param(id)
            .param(fromState.name())
            .update();
        
        if (updated > 0) {
            jdbcClient.sql("""
                INSERT INTO payout_transitions (payout_id, from_state, to_state, reason, at)
                VALUES (?, ?, ?, ?, ?)
                """)
                .param(id)
                .param(fromState.name())
                .param(toState.name())
                .param(reason)
                .param(now)
                .update();
        }
        
        return updated;
    }
    
    /**
     * Update the next check time and increment attempts (for recovery job lease).
     */
    public int claimForRecovery(UUID id, Instant nextCheckAt) {
        return jdbcClient.sql("""
            UPDATE payouts 
            SET next_check_at = ?, attempts = attempts + 1
            WHERE id = ?
            """)
            .param(nextCheckAt)
            .param(id)
            .update();
    }
    
    /**
     * Store the result of a provider call.
     */
    public int updateProviderStatus(UUID id, String providerStatus, String lastError) {
        return jdbcClient.sql("""
            UPDATE payouts 
            SET provider_status = ?, last_error = ?, updated_at = now()
            WHERE id = ?
            """)
            .param(providerStatus)
            .param(lastError)
            .param(id)
            .update();
    }
    
    private Payout mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Payout(
            (UUID) rs.getObject("id"),
            rs.getLong("freelancer_id"),
            rs.getLong("wallet_account_id"),
            rs.getLong("amount_paise"),
            PayoutState.valueOf(rs.getString("state")),
            rs.getString("provider_status"),
            rs.getInt("attempts"),
            rs.getObject("next_check_at", java.sql.Timestamp.class) != null 
                ? rs.getObject("next_check_at", java.sql.Timestamp.class).toInstant() 
                : null,
            rs.getString("last_error"),
            rs.getObject("created_at", java.sql.Timestamp.class).toInstant(),
            rs.getObject("updated_at", java.sql.Timestamp.class).toInstant(),
            rs.getInt("version")
        );
    }
}
