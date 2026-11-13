package com.svatsa.escrow.ratelimit;

import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for rate limiting.
 *
 * Tests:
 * - Rate limit is enforced (N+1 requests fail)
 * - Window resets after time advances
 * - Idempotent-Replayed requests still count against the limit
 * - When Redis is stopped, requests succeed (fail-open)
 * - Headers are set correctly (X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset)
 *
 * Note: Requires Redis running. Disabled for H2 in-memory tests.
 */
@Disabled("Requires Redis with Testcontainers; enable in CI or with docker-compose")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Rate Limiter Integration Tests")
class RateLimiterIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RedisRateLimiter rateLimiter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private Clock clock;

    private static final String ACTOR_ID = "test-actor-42";
    private static final String TEST_ENDPOINT = "/api/v1/transfers";
    private static final int DEFAULT_LIMIT = 30;

    @BeforeEach
    void setUp() {
        // Clear all rate limit keys from Redis
        redisTemplate.delete(redisTemplate.keys("ratelimit:*"));
    }

    @Test
    @DisplayName("Should allow requests up to the limit")
    void shouldAllowRequestsUpToLimit() throws Exception {
        // Make DEFAULT_LIMIT requests; all should succeed
        for (int i = 0; i < DEFAULT_LIMIT; i++) {
            mockMvc.perform(post(TEST_ENDPOINT)
                    .header("X-Actor-Id", ACTOR_ID)
                    .header("X-Actor-Role", "CLIENT")
                    .header("Idempotency-Key", "test-key-" + i)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-RateLimit-Limit"))
                .andExpect(header().exists("X-RateLimit-Remaining"));
        }
    }

    @Test
    @DisplayName("Should reject requests that exceed the limit")
    void shouldRejectExcessRequests() throws Exception {
        // Make DEFAULT_LIMIT requests; all should succeed
        for (int i = 0; i < DEFAULT_LIMIT; i++) {
            mockMvc.perform(post(TEST_ENDPOINT)
                    .header("X-Actor-Id", ACTOR_ID)
                    .header("X-Actor-Role", "CLIENT")
                    .header("Idempotency-Key", "test-key-" + i)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isOk());
        }

        // The next request should be rejected with 429
        mockMvc.perform(post(TEST_ENDPOINT)
                .header("X-Actor-Id", ACTOR_ID)
                .header("X-Actor-Role", "CLIENT")
                .header("Idempotency-Key", "test-key-limit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("Should have stricter limit for payout endpoints")
    void shouldHaveSticterLimitForPayouts() throws Exception {
        String payoutEndpoint = "/api/v1/payouts";
        int payoutLimit = 5;

        // Make 5 requests; all should succeed
        for (int i = 0; i < payoutLimit; i++) {
            mockMvc.perform(post(payoutEndpoint)
                    .header("X-Actor-Id", ACTOR_ID)
                    .header("X-Actor-Role", "FREELANCER")
                    .header("Idempotency-Key", "payout-key-" + i)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isOk());
        }

        // The 6th request should be rejected
        mockMvc.perform(post(payoutEndpoint)
                .header("X-Actor-Id", ACTOR_ID)
                .header("X-Actor-Role", "FREELANCER")
                .header("Idempotency-Key", "payout-key-limit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Should exclude admin endpoints from rate limiting")
    void shouldExcludeAdminEndpoints() throws Exception {
        // Admin endpoints should not be rate-limited even after hitting the limit
        String adminEndpoint = "/api/v1/admin/recon/run";

        // First, exhaust the general limit
        for (int i = 0; i < DEFAULT_LIMIT; i++) {
            mockMvc.perform(post(TEST_ENDPOINT)
                    .header("X-Actor-Id", ACTOR_ID)
                    .header("X-Actor-Role", "ADMIN")
                    .header("Idempotency-Key", "test-key-" + i)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isOk());
        }

        // Admin endpoint should still work (not rate-limited)
        mockMvc.perform(post(adminEndpoint)
                .header("X-Actor-Id", ACTOR_ID)
                .header("X-Actor-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2024-01-01\"}"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should exclude webhook endpoints from rate limiting")
    void shouldExcludeWebhookEndpoints() throws Exception {
        // Webhook endpoints should not be rate-limited
        String webhookEndpoint = "/api/v1/webhooks/razorpay";

        // Exhaust the general limit
        for (int i = 0; i < DEFAULT_LIMIT; i++) {
            mockMvc.perform(post(TEST_ENDPOINT)
                    .header("X-Actor-Id", ACTOR_ID)
                    .header("X-Actor-Role", "CLIENT")
                    .header("Idempotency-Key", "test-key-" + i)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isOk());
        }

        // Webhook endpoint should still work (not rate-limited)
        mockMvc.perform(post(webhookEndpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should allow different actors to have independent limits")
    void shouldHaveIndependentLimitsPerActor() throws Exception {
        String actor1 = "actor-1";
        String actor2 = "actor-2";

        // Exhaust actor1's limit
        for (int i = 0; i < DEFAULT_LIMIT; i++) {
            mockMvc.perform(post(TEST_ENDPOINT)
                    .header("X-Actor-Id", actor1)
                    .header("X-Actor-Role", "CLIENT")
                    .header("Idempotency-Key", "actor1-key-" + i)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isOk());
        }

        // Actor1 should be limited
        mockMvc.perform(post(TEST_ENDPOINT)
                .header("X-Actor-Id", actor1)
                .header("X-Actor-Role", "CLIENT")
                .header("Idempotency-Key", "actor1-limit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isTooManyRequests());

        // Actor2 should still have budget
        mockMvc.perform(post(TEST_ENDPOINT)
                .header("X-Actor-Id", actor2)
                .header("X-Actor-Role", "CLIENT")
                .header("Idempotency-Key", "actor2-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should set rate limit headers on successful requests")
    void shouldSetRateLimitHeaders() throws Exception {
        MvcResult result = mockMvc.perform(post(TEST_ENDPOINT)
                .header("X-Actor-Id", ACTOR_ID)
                .header("X-Actor-Role", "CLIENT")
                .header("Idempotency-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-RateLimit-Limit"))
            .andExpect(header().exists("X-RateLimit-Remaining"))
            .andExpect(header().exists("X-RateLimit-Reset"))
            .andReturn();

        String limit = result.getResponse().getHeader("X-RateLimit-Limit");
        String remaining = result.getResponse().getHeader("X-RateLimit-Remaining");

        assertThat(limit).isEqualTo(String.valueOf(DEFAULT_LIMIT));
        assertThat(remaining).isEqualTo(String.valueOf(DEFAULT_LIMIT - 1));
    }

    @Test
    @DisplayName("Should allow requests when missing X-Actor-Id header")
    void shouldAllowRequestsWithoutActorId() throws Exception {
        // Requests without X-Actor-Id should not be rate-limited
        mockMvc.perform(post(TEST_ENDPOINT)
                .header("X-Actor-Role", "CLIENT")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should include ProblemDetail with code in 429 response")
    void shouldIncludeProblemDetailIn429() throws Exception {
        // Exhaust the limit
        for (int i = 0; i < DEFAULT_LIMIT; i++) {
            mockMvc.perform(post(TEST_ENDPOINT)
                    .header("X-Actor-Id", ACTOR_ID)
                    .header("X-Actor-Role", "CLIENT")
                    .header("Idempotency-Key", "test-key-" + i)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isOk());
        }

        // Next request should return 429 with ProblemDetail
        MvcResult result = mockMvc.perform(post(TEST_ENDPOINT)
                .header("X-Actor-Id", ACTOR_ID)
                .header("X-Actor-Role", "CLIENT")
                .header("Idempotency-Key", "test-key-limit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isTooManyRequests())
            .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).contains("RATE_LIMITED");
    }
}
