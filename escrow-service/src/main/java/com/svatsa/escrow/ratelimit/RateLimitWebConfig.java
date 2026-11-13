package com.svatsa.escrow.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring Web MVC configuration for rate limiting.
 * Registers the RateLimitInterceptor and enables RateLimitConfig property binding.
 */
@Configuration
@EnableConfigurationProperties(RateLimitConfig.class)
public class RateLimitWebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    public RateLimitWebConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Register the rate limit interceptor for all requests
        registry.addInterceptor(rateLimitInterceptor)
            .addPathPatterns("/**");
    }
}
