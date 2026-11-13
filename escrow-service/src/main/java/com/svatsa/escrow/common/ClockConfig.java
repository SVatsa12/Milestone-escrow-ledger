package com.svatsa.escrow.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

/**
 * Provides a Clock bean for dependency injection.
 * 
 * In production, returns a system UTC clock.
 * MutableClock for tests can be wired in manually via test-specific config if needed.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
