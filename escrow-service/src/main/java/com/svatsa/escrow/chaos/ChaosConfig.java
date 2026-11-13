package com.svatsa.escrow.chaos;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chaos engineering configuration for testing resilience.
 *
 * Controlled by spring.profiles.active=chaos and chaos.crash-point environment variable.
 *
 * Crash points:
 * - NONE (default): no crashes
 * - AFTER_RESERVE: crash after reserving a payout (before calling provider)
 * - AFTER_PROVIDER_CALL: crash after provider call (before confirming/reversing)
 *
 * These simulate mid-saga failures to test recovery jobs and idempotency.
 */
@Configuration
@ConditionalOnProperty(name = "chaos.crash-point", havingValue = "NONE", matchIfMissing = true)
public class ChaosConfig {

    private static final Logger log = LoggerFactory.getLogger(ChaosConfig.class);

    public enum CrashPoint {
        NONE,
        AFTER_RESERVE,
        AFTER_PROVIDER_CALL
    }

    private static CrashPoint crashPoint = CrashPoint.NONE;

    public static void setCrashPoint(CrashPoint point) {
        crashPoint = point;
        log.info("Chaos crash point set to: {}", point);
    }

    public static CrashPoint getCrashPoint() {
        return crashPoint;
    }

    public static void crashIfNeeded(CrashPoint triggerPoint) {
        if (crashPoint == triggerPoint) {
            log.error("CHAOS: Crashing at {}", triggerPoint);
            Runtime.getRuntime().halt(137); // Exit code 137 = killed by signal 9
        }
    }

    public static boolean isChaosEnabled() {
        return crashPoint != CrashPoint.NONE;
    }
}
