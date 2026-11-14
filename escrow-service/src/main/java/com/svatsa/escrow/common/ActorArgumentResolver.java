package com.svatsa.escrow.common;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Builds an {@link Actor} controller argument from the X-Actor-Id and X-Actor-Role headers.
 * Missing or invalid headers are 401 UNAUTHENTICATED. SYSTEM is internal only, so a header that
 * claims it is invalid too.
 */
public class ActorArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String ACTOR_ID = "X-Actor-Id";
    public static final String ACTOR_ROLE = "X-Actor-Role";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Actor.class;
    }

    @Override
    public Actor resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                 NativeWebRequest request, WebDataBinderFactory binderFactory) {
        return fromHeaders(request.getHeader(ACTOR_ID), request.getHeader(ACTOR_ROLE));
    }

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
