package com.svatsa.provider;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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

    /** All provider timestamps and the ASYNC scheduler read this, so tests can move time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
