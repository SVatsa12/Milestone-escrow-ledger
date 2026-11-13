package com.svatsa.escrow.escrow;

import java.time.Instant;

/**
 * User record: client or freelancer.
 */
public record User(
    Long id,
    String name,
    String role, // CLIENT, FREELANCER, ADMIN
    Instant createdAt
) {
    public User {
        if (id == null || id <= 0) throw new IllegalArgumentException("id must be positive");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name cannot be blank");
        if (role == null || !role.matches("CLIENT|FREELANCER|ADMIN")) {
            throw new IllegalArgumentException("role must be CLIENT, FREELANCER, or ADMIN");
        }
        if (createdAt == null) throw new IllegalArgumentException("createdAt cannot be null");
    }
}
