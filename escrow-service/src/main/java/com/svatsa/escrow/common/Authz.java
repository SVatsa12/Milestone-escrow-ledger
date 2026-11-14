package com.svatsa.escrow.common;

import org.springframework.http.HttpStatus;

/**
 * Service-layer authorization checks (AGENTS.md: auth headers are checked in the service layer).
 * A failed check is 403 FORBIDDEN.
 */
public final class Authz {

    private Authz() {
    }

    /** 403 unless {@code allowed}. {@code action} reads as "may not {action}". */
    public static void require(boolean allowed, Actor actor, String action) {
        if (!allowed) {
            throw forbidden(actor, action);
        }
    }

    public static void requireAdmin(Actor actor, String action) {
        require(actor.role() == Role.ADMIN, actor, action);
    }

    public static DomainException forbidden(Actor actor, String action) {
        return new DomainException("FORBIDDEN", HttpStatus.FORBIDDEN, actor + " may not " + action);
    }
}
