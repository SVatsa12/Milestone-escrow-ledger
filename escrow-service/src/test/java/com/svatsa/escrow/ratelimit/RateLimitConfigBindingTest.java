package com.svatsa.escrow.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml used to put these settings under a top-level "ratelimit:" key while
 * RateLimitConfig binds "escrow.ratelimit". Nothing bound, every field defaulted to false/0,
 * and rate limiting was silently disabled in every environment.
 */
class RateLimitConfigBindingTest {

    @Test
    void applicationYmlBindsRateLimitConfig() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
            .forEach(env.getPropertySources()::addLast);

        RateLimitConfig config = new Binder(ConfigurationPropertySources.get(env),
                new org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver(env))
            .bind("escrow.ratelimit", RateLimitConfig.class)
            .orElseThrow(() -> new AssertionError("escrow.ratelimit is not configured in application.yml"));

        assertThat(config.enabled()).isTrue();
        assertThat(config.defaultLimit()).isEqualTo(30);
        assertThat(config.payoutLimit()).isEqualTo(5);
        assertThat(config.windowSeconds()).isEqualTo(60);
    }
}
