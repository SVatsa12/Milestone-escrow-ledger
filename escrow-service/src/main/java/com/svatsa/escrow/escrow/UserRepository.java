package com.svatsa.escrow.escrow;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Repository for User records.
 * Uses JdbcClient to keep money flows compatible with the ledger.
 */
@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Finds a user by ID.
     */
    public Optional<User> findById(Long id) {
        return jdbc.sql("SELECT id, name, role, created_at FROM users WHERE id = ?")
            .param(id)
            .query(User.class)
            .optional();
    }

    /**
     * Saves a new user and returns it with the auto-generated ID.
     */
    public User save(String name, String role) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
        if (role == null || !role.matches("CLIENT|FREELANCER|ADMIN")) {
            throw new IllegalArgumentException("role must be CLIENT, FREELANCER, or ADMIN");
        }

        Long id = jdbc.sql("INSERT INTO users (name, role, created_at) VALUES (?, ?, now()) RETURNING id")
            .param(name)
            .param(role)
            .query(Long.class)
            .single();

        return findById(id)
            .orElseThrow(() -> new IllegalStateException("Could not retrieve saved user"));
    }
}
