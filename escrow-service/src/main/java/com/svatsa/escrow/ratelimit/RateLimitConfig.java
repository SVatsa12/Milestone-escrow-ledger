package com.svatsa.escrow.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Configuration for rate limiting.
 * Read from escrow.ratelimit.* properties.
 *
 * Example:
 * escrow:
 *   ratelimit:
 *     enabled: true
 *     default-limit: 30          # requests per minute for general money POSTs
 *     payout-limit: 5            # requests per minute for payout POSTs
 *     window-seconds: 60         # rate limit window size
 */
@ConfigurationProperties(prefix = "escrow.ratelimit")
public record RateLimitConfig(
    boolean enabled,              // whether rate limiting is active
    int defaultLimit,             // requests per minute for general money operations
    int payoutLimit,              // requests per minute for payout operations (stricter)
    int windowSeconds             // duration of each rate limit window
) {
    @ConstructorBinding
    public RateLimitConfig {
        // Defaults if not specified
    }

    public static RateLimitConfig defaults() {
        return new RateLimitConfig(
            true,    // enabled by default
            30,      // 30 requests per minute for general operations
            5,       // 5 requests per minute for payout operations
            60       // 60-second window
        );
    }

    public int getLimitForEndpoint(String path) {
        // Payouts have a stricter limit
        if (path.contains("/payouts")) {
            return payoutLimit;
        }
        return defaultLimit;
    }
}
