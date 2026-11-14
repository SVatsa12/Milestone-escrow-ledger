package com.svatsa.escrow.common;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Builds an {@link Actor} controller argument from the X-Actor-Id and X-Actor-Role headers, and
 * checks both against the users table: the id must be a real user and the claimed role must be
 * that user's stored role. Otherwise it is 401 UNAUTHENTICATED: missing or malformed headers, an
 * unknown id, or a role that doesn't match (a CLIENT sending X-Actor-Role: ADMIN). SYSTEM is
 * internal only, so a header that claims it is invalid too.
 *
 * Found roles are cached. That is safe because a user's id and role can never change and users
 * are never deleted (V11 triggers). Unknown ids are not cached: the user may be created later.
 */
@Component
public class ActorArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String ACTOR_ID = "X-Actor-Id";
    public static final String ACTOR_ROLE = "X-Actor-Role";

    private static final Logger log = LoggerFactory.getLogger(ActorArgumentResolver.class);
    private static final int MAX_CACHED = 100_000;

    private final JdbcClient jdbc;
    private final Map<Long, Role> storedRoles = new ConcurrentHashMap<>();

    public ActorArgumentResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Actor.class;
    }

    @Override
    public Actor resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                 NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Actor claimed = fromHeaders(request.getHeader(ACTOR_ID), request.getHeader(ACTOR_ROLE));
        Role stored = storedRole(claimed.id())
            .orElseThrow(() -> {
                log.warn("auth.rejected reason=unknown-actor actorId={} claimedRole={}", claimed.id(), claimed.role());
                return unauthenticated("Unknown actor " + claimed.id());
            });
        if (stored != claimed.role()) {
            log.warn("auth.rejected reason=role-mismatch actorId={} claimedRole={} storedRole={}",
                claimed.id(), claimed.role(), stored);
            throw unauthenticated("Actor " + claimed.id() + " is not " + claimed.role());
        }
        return claimed;
    }

    private Optional<Role> storedRole(long id) {
        Role cached = storedRoles.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Role> found = jdbc.sql("SELECT role FROM users WHERE id = ?")
            .param(id)
            .query(String.class)
            .optional()
            .map(Role::valueOf);
        found.ifPresent(role -> {
            if (storedRoles.size() >= MAX_CACHED) {
                storedRoles.clear();   // crude bound; entries are cheap to reload
            }
            storedRoles.put(id, role);
        });
        return found;
    }

    /** Parses the headers only (no lookup). */
    static Actor fromHeaders(String idHeader, String roleHeader) {
        if (idHeader == null || idHeader.isBlank() || roleHeader == null || roleHeader.isBlank()) {
            throw unauthenticated(ACTOR_ID + " and " + ACTOR_ROLE + " headers are required");
        }
        long id;
        try {
            id = Long.parseLong(idHeader.trim());
        } catch (NumberFormatException e) {
            throw unauthenticated(ACTOR_ID + " must be a positive number");
        }
        if (id <= 0) {
            throw unauthenticated(ACTOR_ID + " must be a positive number");
        }
        Role role = switch (roleHeader.trim()) {
            case "CLIENT" -> Role.CLIENT;
            case "FREELANCER" -> Role.FREELANCER;
            case "ADMIN" -> Role.ADMIN;
            default -> throw unauthenticated(ACTOR_ROLE + " must be CLIENT, FREELANCER or ADMIN");
        };
        return new Actor(id, role);
    }

    private static DomainException unauthenticated(String detail) {
        return new DomainException("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED, detail);
    }
}
