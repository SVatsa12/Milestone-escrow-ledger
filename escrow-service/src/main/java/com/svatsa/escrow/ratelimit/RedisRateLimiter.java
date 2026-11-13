package com.svatsa.escrow.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Redis-backed rate limiter using a Lua script for atomic operations.
 *
 * Uses a fixed-window counter approach:
 * - Key: ratelimit:{actorId}:{bucket}:{window}
 * - Lua script atomically increments the counter and sets TTL
 * - Returns the count after increment; if it exceeds the limit, the request is rejected
 *
 * The script is atomic at the Redis level, so no race conditions between check and increment.
 */
@Component
public class RedisRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final RedisScript<Long> rateLimitScript;

    // Lua script for atomic increment with TTL
    // KEYS[1] = the rate limit key
    // ARGV[1] = limit (max requests allowed in window)
    // ARGV[2] = window size in seconds (TTL)
    // Returns: the count after increment, or -1 if limit exceeded
    private static final String RATE_LIMIT_LUA = """
        local key = KEYS[1]
        local limit = tonumber(ARGV[1])
        local window = tonumber(ARGV[2])
        
        local current = redis.call('incr', key)
        if current == 1 then
            redis.call('expire', key, window)
        end
        
        if current > limit then
            return -1
        else
            return current
        end
        """;

    public RedisRateLimiter(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.rateLimitScript = RedisScript.of(RATE_LIMIT_LUA, Long.class);
    }

    /**
     * Check if a request is allowed under the rate limit.
     * Uses a fixed-window counter approach.
     *
     * @param actorId the actor making the request (user ID)
     * @param endpoint the endpoint path (e.g., /api/v1/transfers, /api/v1/payouts)
     * @param limit max requests allowed per window
     * @param windowSeconds window size in seconds (typically 60 for "per minute")
     * @return RateLimitResult with allowed status, remaining requests, and reset time
     */
    public RateLimitResult checkLimit(String actorId, String endpoint, int limit, int windowSeconds) {
        try {
            String key = buildKey(actorId, endpoint);
            Long count = redisTemplate.execute(
                rateLimitScript,
                List.of(key),
                String.valueOf(limit),
                String.valueOf(windowSeconds)
            );

            if (count == null) {
                // Script execution error; default to fail-open (allow)
                return RateLimitResult.allowed(limit);
            }

            if (count == -1) {
                // Limit exceeded
                long now = clock.instant().getEpochSecond();
                long windowStart = (now / windowSeconds) * windowSeconds;
                long resetTime = windowStart + windowSeconds;
                return RateLimitResult.limitExceeded(resetTime);
            } else {
                // Request allowed
                int remaining = (int) Math.max(0, limit - count);
                long now = clock.instant().getEpochSecond();
                long windowStart = (now / windowSeconds) * windowSeconds;
                long resetTime = windowStart + windowSeconds;
                return RateLimitResult.allowed(remaining, resetTime);
            }
        } catch (Exception e) {
            // Fail open: if Redis is down or unreachable, allow the request
            // Log the error for monitoring
            return RateLimitResult.failOpen();
        }
    }

    /**
     * Reset the rate limit for a specific actor/endpoint combination.
     * Useful for testing or manual admin operations.
     */
    public void reset(String actorId, String endpoint) {
        try {
            String key = buildKey(actorId, endpoint);
            redisTemplate.delete(key);
        } catch (Exception e) {
            // Silently ignore reset failures
        }
    }

    /**
     * Get the current count for debugging.
     */
    public long getCurrentCount(String actorId, String endpoint) {
        try {
            String key = buildKey(actorId, endpoint);
            String value = redisTemplate.opsForValue().get(key);
            return value != null ? Long.parseLong(value) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private String buildKey(String actorId, String endpoint) {
        long now = clock.instant().getEpochSecond();
        long windowSize = 60; // Fixed 60-second windows; could be configurable
        long windowStart = (now / windowSize) * windowSize;
        // Key format: ratelimit:{actorId}:{endpoint_hash}:{window_start}
        return String.format("ratelimit:%s:%s:%d", actorId, endpoint, windowStart);
    }

    /**
     * Result of a rate limit check.
     */
    public static class RateLimitResult {
        public final boolean allowed;
        public final boolean redisDown;
        public final int remaining;
        public final long resetTime; // Unix timestamp when the window resets

        private RateLimitResult(boolean allowed, boolean redisDown, int remaining, long resetTime) {
            this.allowed = allowed;
            this.redisDown = redisDown;
            this.remaining = remaining;
            this.resetTime = resetTime;
        }

        public static RateLimitResult allowed(int remaining) {
            return new RateLimitResult(true, false, remaining, 0);
        }

        public static RateLimitResult allowed(int remaining, long resetTime) {
            return new RateLimitResult(true, false, remaining, resetTime);
        }

        public static RateLimitResult limitExceeded(long resetTime) {
            return new RateLimitResult(false, false, 0, resetTime);
        }

        public static RateLimitResult failOpen() {
            return new RateLimitResult(true, true, -1, 0);
        }

        @Override
        public String toString() {
            if (redisDown) {
                return "RateLimitResult{allowed=true, redis_down=true}";
            }
            return "RateLimitResult{allowed=" + allowed + ", remaining=" + remaining + ", resetTime=" + resetTime + "}";
        }
    }
}
