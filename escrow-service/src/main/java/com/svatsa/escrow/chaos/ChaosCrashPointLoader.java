package com.svatsa.escrow.chaos;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Arms {@link ChaosConfig}'s crash point from {@code chaos.crash-point} (env
 * {@code CHAOS_CRASH_POINT}), only under the {@code chaos} profile.
 *
 * Nothing used to read the property at all: the crash point could only be set from code, so K1's
 * {@code CHAOS_CRASH_POINT=AFTER_RESERVE} did nothing and no container ever died mid-saga.
 */
@Component
@Profile("chaos")
public class ChaosCrashPointLoader {

    public ChaosCrashPointLoader(@Value("${chaos.crash-point:NONE}") ChaosConfig.CrashPoint crashPoint) {
        ChaosConfig.setCrashPoint(crashPoint);
    }
}
