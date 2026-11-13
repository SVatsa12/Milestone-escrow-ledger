package com.svatsa.escrow.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;

/**
 * Redis-backed rate limiter using fixed-window buckets.
 * 
 * Implements a Lua script to atomically check and increment a counter per actor per minute.
 * If Redis is unavailable (connection error), logs a warning and allows the request (fail-open).
 */
@Component
public class RedisRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    
    /**
     * Lua script that atomically:
     * 1. Gets the current counter value for the key
     * 2. Increments if below the limit (or creates with EXPIRE if key doesn't exist)
     * 3. Returns {allowRequest: 1/0, currentCount: N, resetAtSeconds: unix}
     * 
     * Keys: KEYS[1] = ratelimit key
     * Args: ARGV[1] = limit, ARGV[2] = window duration in seconds, ARGV[3] = current unix seconds
     */
    private static final String LUA_SCRIPT = """
        local key = KEYS[1]
        local limit = tonumber(ARGV[1])
        local windowSecs = tonumber(ARGV[2])
        local nowSecs = tonumber(ARGV[3])
        
        -- Get current count (if key exists)
        local current = redis.call('GET', key)
        if current == false then
            current = 0
        else
            current = tonumber(current)
        end
        
        -- Calculate window end (aligned to minutes)
        local windowStart = math.floor(nowSecs / windowSecs) * windowSecs
        local windowEnd = windowStart + windowSecs
        
        -- Check if this is a new window (key was from a previous window)
        local ttl = redis.call('TTL', key)
        local isNewWindow = (ttl == -2) or (ttl == -1 and current == 0)
        
        if isNewWindow then
            current = 0
        end
        
        -- Check limit
        local allowRequest = 1
        if current >= limit then
            allowRequest = 0
        else
            current = current + 1
            redis.call('SET', key, current, 'EX', windowSecs)
        end
        
        return {allowRequest, current, windowEnd}
        """;
    
    private final RedisScript<java.util.List> rateLimitScript;
    
    public RedisRateLimiter(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        // Compile the Lua script
        this.rateLimitScript = RedisScript.of(LUA_SCRIPT);
    }
    
    /**
     * Check if a request from an actor is within the rate limit.
     * 
     * @param actorId unique identifier for the requester
     * @param bucket operation type (e.g., "transfer", "payout")
     * @param limitPerMinute maximum requests per minute
     * @return RateLimitResult with allowRequest flag and reset time
     */
    public RateLimitResult checkLimit(long actorId, String bucket, int limitPerMinute) {
        try {
            String key = "ratelimit:%d:%s:%d".formatted(actorId, bucket, getCurrentWindow());
            long nowSeconds = clock.instant().getEpochSecond();
            int windowSeconds = 60; // 1-minute window
            
            var result = redisTemplate.execute(
                rateLimitScript,
                Collections.singletonList(key),
                String.valueOf(limitPerMinute),
                String.valueOf(windowSeconds),
                String.valueOf(nowSeconds)
            );
            
            if (result == null || result.size() < 3) {
                // Unexpected response; fail open with a warning
                log.warn("Unexpected rate limit script response, failing open");
                return new RateLimitResult(true, 0, Instant.ofEpochSecond(nowSeconds + windowSeconds));
            }
            
            boolean allowed = !((Number) result.get(0)).equals(0);
            long currentCount = ((Number) result.get(1)).longValue();
            long resetAtSeconds = ((Number) result.get(2)).longValue();
            
            return new RateLimitResult(allowed, currentCount, Instant.ofEpochSecond(resetAtSeconds));
        } catch (Exception e) {
            // Redis unavailable or any other error: fail open
            log.warn("Rate limiter failed, failing open (Redis may be unavailable)", e);
            long resetAtSeconds = clock.instant().getEpochSecond() + 60;
            return new RateLimitResult(true, 0, Instant.ofEpochSecond(resetAtSeconds));
        }
    }
    
    /**
     * Get the current minute window (for key bucketing).
     * Window changes once per minute.
     */
    private long getCurrentWindow() {
        long nowSeconds = clock.instant().getEpochSecond();
        return nowSeconds / 60;
    }
    
    /**
     * Result of a rate limit check.
     * 
     * @param allowed true if the request is within limits
     * @param currentCount current request count in this window
     * @param resetAt when the window resets (unix instant)
     */
    public record RateLimitResult(
        boolean allowed,
        long currentCount,
        java.time.Instant resetAt
    ) {}
}
