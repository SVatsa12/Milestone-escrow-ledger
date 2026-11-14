package com.svatsa.provider;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * A deliberately unreliable payout bank for tests and chaos scripts. Its behaviour is set at
 * runtime through POST /admin/mode; its state lives in Postgres schema "provider".
 */
@SpringBootApplication
@EnableScheduling
public class PayoutProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayoutProviderApplication.class, args);
    }

    /**
     * All provider timestamps and the ASYNC scheduler read this, so tests can move time. A test
     * that runs the provider in-process may register its own Clock before the context starts
     * (escrow-service's tests share one clock between both apps); then this one isn't created.
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock clock() {
        return Clock.systemUTC();
    }
}
