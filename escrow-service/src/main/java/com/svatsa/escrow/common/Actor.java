package com.svatsa.escrow.common;

import java.util.Objects;

/**
 * The caller of a use case. Built from the X-Actor-Id / X-Actor-Role headers by
 * {@link ActorArgumentResolver}, or {@link #SYSTEM} for internal jobs. Every escrow, dispute,
 * deposit and payout service method takes one and checks it ({@link Authz}).
 */
public record Actor(long id, Role role) {

    /** The service itself (auto-release, recovery jobs). Never built from request headers. */
    public static final Actor SYSTEM = new Actor(0, Role.SYSTEM);

    public Actor {
        Objects.requireNonNull(role, "role");
        if (role != Role.SYSTEM && id <= 0) {
            throw new IllegalArgumentException("actor id must be positive");
        }
    }

    /** True when this actor is the given user acting in the given role. */
    public boolean is(Role expectedRole, long userId) {
        return role == expectedRole && id == userId;
    }

    public boolean isSystem() {
        return role == Role.SYSTEM;
    }

    /** The id to store in audit columns: NULL for SYSTEM, which is not a user. */
    public Long auditId() {
        return isSystem() ? null : id;
    }

    @Override
    public String toString() {
        return role + ":" + id;
    }
}
