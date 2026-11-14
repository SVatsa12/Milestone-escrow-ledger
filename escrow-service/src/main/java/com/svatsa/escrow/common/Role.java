package com.svatsa.escrow.common;

/**
 * Who is acting. CLIENT, FREELANCER and ADMIN come from the X-Actor-Role header (AGENTS.md: auth
 * is a stub). SYSTEM is the service itself (auto-release, payout recovery): it is created only in
 * code, and {@link ActorArgumentResolver} rejects it in a header.
 */
public enum Role {
    CLIENT,
    FREELANCER,
    ADMIN,
    SYSTEM
}
