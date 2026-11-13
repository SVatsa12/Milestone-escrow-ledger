package com.svatsa.escrow.ratelimit;

import com.svatsa.escrow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * HTTP interceptor that enforces rate limits on money-moving endpoints.
 * 
 * Intercepts POST /api/v1/** (excluding admin and webhooks) and checks rate limits
 * per actor. If rate limit exceeded, returns 429 with Retry-After header.
 * 
 * If Redis is unavailable, the check fails open and the request proceeds.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);
    
    private final RedisRateLimiter rateLimiter;
    private final RateLimitProperties config;
    
    public RateLimitInterceptor(RedisRateLimiter rateLimiter, RateLimitProperties config) {
        this.rateLimiter = rateLimiter;
        this.config = config;
    }
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String method = request.getMethod();
        String path = request.getRequestURI();
        
        // Only rate-limit POST requests to /api/v1/**
        if (!"POST".equals(method)) {
            return true;
        }
        
        // Skip admin and webhook endpoints
        if (path.startsWith("/api/v1/admin/") || path.startsWith("/webhooks/")) {
            return true;
        }
        
        // Skip non-v1 endpoints
        if (!path.startsWith("/api/v1/")) {
            return true;
        }
        
        // Extract actor ID from header
        String actorIdStr = request.getHeader("X-Actor-Id");
        if (actorIdStr == null || actorIdStr.isBlank()) {
            log.warn("Rate limit check skipped: missing X-Actor-Id header");
            return true;
        }
        
        long actorId;
        try {
            actorId = Long.parseLong(actorIdStr);
        } catch (NumberFormatException e) {
            log.warn("Rate limit check skipped: invalid X-Actor-Id: {}", actorIdStr);
            return true;
        }
        
        // Determine bucket based on endpoint
        String bucket = determineBucket(path);
        int limit = determineLimitForBucket(bucket);
        
        // Check rate limit
        RedisRateLimiter.RateLimitResult result = rateLimiter.checkLimit(actorId, bucket, limit);
        
        if (!result.allowed()) {
            log.warn("Rate limit exceeded for actor {} on {}", actorId, path);
            
            // Return 429 with Retry-After header
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            
            // Set Retry-After header (RFC 7231: seconds or HTTP date)
            String retryAfter = calculateRetryAfter(result.resetAt());
            response.setHeader("Retry-After", retryAfter);
            
            // Set Content-Type and write error response
            response.setContentType("application/problem+json");
            String errorBody = """
                {
                  "type": "about:blank",
                  "status": 429,
                  "code": "RATE_LIMITED",
                  "title": "Too Many Requests",
                  "detail": "Rate limit exceeded. Retry after %s seconds.",
                  "instance": "%s"
                }
                """.formatted(calculateRetrySeconds(result.resetAt()), path);
            response.getWriter().write(errorBody);
            return false;
        }
        
        return true;
    }
    
    /**
     * Determine the bucket (operation type) based on the request path.
     */
    private String determineBucket(String path) {
        if (path.contains("/payouts")) {
            return "payout";
        }
        // Default for all other money-moving endpoints (transfers, escrow operations, etc.)
        return "transfer";
    }
    
    /**
     * Get the rate limit for a bucket.
     */
    private int determineLimitForBucket(String bucket) {
        return "payout".equals(bucket)
            ? config.getPayoutRequestsPerMinute()
            : config.getTransferRequestsPerMinute();
    }
    
    /**
     * Calculate Retry-After header value (seconds until reset).
     */
    private String calculateRetrySeconds(Instant resetAt) {
        long now = System.currentTimeMillis();
        long resetMs = resetAt.toEpochMilli();
        long secondsUntilReset = Math.max(1, (resetMs - now) / 1000);
        return String.valueOf(secondsUntilReset);
    }
    
    /**
     * Calculate Retry-After header value (can be in seconds or HTTP date).
     * We use seconds format for simplicity (RFC 7231 allows both).
     */
    private String calculateRetryAfter(Instant resetAt) {
        return calculateRetrySeconds(resetAt);
    }
}
