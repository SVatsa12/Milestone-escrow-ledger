package com.svatsa.escrow.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * Low-level account data access using JdbcClient for direct SQL control.
 * 
 * This is not a Spring Data repository; it's a thin wrapper around JDBC
 * for explicit transaction control and to avoid ORM abstraction overhead.
 */
@Repository
public class AccountRepository {

    private final JdbcClient jdbcClient;

    public AccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Find an account by its code (business key).
     */
    public Optional<Account> findByCode(String code) {
        return jdbcClient.sql("""
            SELECT id, code, type, owner_id, allow_negative, balance, created_at
            FROM accounts
            WHERE code = ?
            """)
            .param(code)
            .query(Account.class)
            .optional();
    }

    /**
     * Find an account by its ID.
     */
    public Optional<Account> findById(long id) {
        return jdbcClient.sql("""
            SELECT id, code, type, owner_id, allow_negative, balance, created_at
            FROM accounts
            WHERE id = ?
            """)
            .param(id)
            .query(Account.class)
            .optional();
    }

    /**
     * Create a new account if it doesn't already exist (keyed on code).
     * 
     * Uses INSERT ... ON CONFLICT DO NOTHING to make it safe to call twice.
     * Returns the account (either newly created or existing).
     * 
     * @param code unique business identifier (e.g., "CLIENT_WALLET:123")
     * @param type account type
     * @param ownerId user ID (nullable)
     * @param allowNegative whether balance can go negative
     * @return the account (newly created or existing)
     */
    public Account create(String code, AccountType type, Long ownerId, boolean allowNegative) {
        jdbcClient.sql("""
            INSERT INTO accounts (code, type, owner_id, allow_negative)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (code) DO NOTHING
            """)
            .param(1, code)
            .param(2, type.name())
            .param(3, ownerId)
            .param(4, allowNegative)
            .update();

        // Always select it after insert, since it may have existed
        return findByCode(code)
            .orElseThrow(() -> new IllegalStateException("Account not found after create: " + code));
    }
}
