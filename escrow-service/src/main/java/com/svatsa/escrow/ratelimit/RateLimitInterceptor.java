package com.svatsa.escrow.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;

/**
 * HandlerInterceptor for rate limiting money-moving endpoints.
 *
 * Applied to:
 * - POST /api/v1/** (general money operations: transfers, milestones, disputes, payouts)
 * Excluded:
 * - /api/v1/webhooks/** (webhook endpoints from external providers, not rate-limited)
 * - /api/v1/admin/** (admin endpoints not rate-limited)
 *
 * Rate limits:
 * - General: 30 requests per minute per actor
 * - Payouts: 5 requests per minute per actor (stricter)
 *
 * Fail-open strategy:
 * - If Redis is down, the request is ALLOWED (the rate limiter returns failOpen())
 * - A warning is logged and a metric is incremented
 * - This ensures money operations never fail due to rate limiting infrastructure issues
 *
 * Idempotent-Replayed requests still count against the limit (they're not free retries).
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RedisRateLimiter rateLimiter;
    private final RateLimitConfig config;
    private final MeterRegistry meterRegistry;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final Clock clock;

    public RateLimitInterceptor(
        RedisRateLimiter rateLimiter,
        RateLimitConfig config,
        MeterRegistry meterRegistry,
        com.fasterxml.jackson.databind.ObjectMapper objectMapper,
        Clock clock
    ) {
        this.clock = clock;
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.meterRegistry = meterRegistry;
        // Spring's mapper flattens ProblemDetail properties into top-level members (RFC 9457).
        // A plain new ObjectMapper() nested them under "properties", so a 429 had no "code".
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        // Only intercept POST requests
        if (!"POST".equals(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();

        // Exclude webhook endpoints
        if (path.contains("/webhooks/")) {
            return true;
        }

        // Exclude admin endpoints
        if (path.contains("/admin/")) {
            return true;
        }

        // Only rate-limit /api/v1/** endpoints
        if (!path.startsWith("/api/v1/")) {
            return true;
        }

        // Rate limiting is disabled
        if (!config.enabled()) {
            return true;
        }

        // Extract actor ID from request headers
        String actorId = request.getHeader("X-Actor-Id");
        if (actorId == null || actorId.isBlank()) {
            // No actor ID; allow (unauthenticated requests are handled elsewhere)
            return true;
        }

        // Determine the rate limit based on the endpoint
        int limit = config.getLimitForEndpoint(path);

        // Check the rate limit
        RedisRateLimiter.RateLimitResult result = rateLimiter.checkLimit(
            actorId,
            path,
            limit,
            config.windowSeconds()
        );

        // Metrics
        if (result.redisDown) {
            meterRegistry.counter("ratelimit.redis_down", "path", path).increment();
            log.warn("Rate limiting: Redis is down, allowing request for actor={} path={}", actorId, path);
        }

        if (!result.allowed) {
            // Rate limit exceeded
            meterRegistry.counter("ratelimit.exceeded", "path", path, "actor", actorId).increment();
            log.warn("Rate limit exceeded for actor={} path={}", actorId, path);

            // Return 429 Too Many Requests
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/problem+json");
            // The window is keyed on the injected Clock (RedisRateLimiter), so Retry-After must be too.
            response.addHeader("Retry-After", String.valueOf(Math.max(0, result.resetTime - clock.instant().getEpochSecond())));

            // ProblemDetail response
            ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded"
            );
            detail.setProperty("code", "RATE_LIMITED");
            detail.setProperty("limit", limit);
            detail.setProperty("resetTime", result.resetTime);

            String json = objectMapper
                .writeValueAsString(detail);
            response.getWriter().write(json);

            return false;
        }

        // Request allowed
        meterRegistry.counter("ratelimit.allowed", "path", path).increment();
        response.addHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.addHeader("X-RateLimit-Remaining", String.valueOf(result.remaining));
        if (result.resetTime > 0) {
            response.addHeader("X-RateLimit-Reset", String.valueOf(result.resetTime));
        }

        return true;
    }
}
