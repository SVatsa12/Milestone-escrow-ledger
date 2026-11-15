package com.svatsa.escrow.chaos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Crash points for chaos scenario K1 (chaos/kill_mid_saga.py): the payout saga calls
 * {@link #crashIfNeeded} at fixed points, and if that point is armed the JVM halts with status 137,
 * exactly like a {@code kill -9}, with no shutdown hooks and no chance to finish the transaction.
 *
 * <ul>
 *   <li>NONE (default): no crashes</li>
 *   <li>AFTER_RESERVE: after the reservation (T1) committed, before the provider is called</li>
 *   <li>AFTER_PROVIDER_CALL: after the provider answered, before confirm/reverse (T2)</li>
 * </ul>
 *
 * The point is armed from {@code chaos.crash-point} (env {@code CHAOS_CRASH_POINT}) by
 * {@link ChaosCrashPointLoader}, which exists only under the {@code chaos} profile: a stray
 * environment variable in production can't make the service kill itself.
 */
public final class ChaosConfig {

    private static final Logger log = LoggerFactory.getLogger(ChaosConfig.class);

    public enum CrashPoint {
        NONE,
        AFTER_RESERVE,
        AFTER_PROVIDER_CALL
    }

    private static volatile CrashPoint crashPoint = CrashPoint.NONE;

    private ChaosConfig() {
    }

    public static void setCrashPoint(CrashPoint point) {
        crashPoint = point;
        log.warn("chaos.crash-point armed point={}", point);
    }

    public static CrashPoint getCrashPoint() {
        return crashPoint;
    }

    public static void crashIfNeeded(CrashPoint triggerPoint) {
        if (crashPoint == triggerPoint) {
            log.error("chaos.crash point={} status=137", triggerPoint);
            Runtime.getRuntime().halt(137); // what a kill -9 looks like from outside
        }
    }

    public static boolean isChaosEnabled() {
        return crashPoint != CrashPoint.NONE;
    }
}
