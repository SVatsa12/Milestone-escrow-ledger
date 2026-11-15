package com.svatsa.escrow.ratelimit;

import com.svatsa.escrow.ledger.Account;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.AccountType;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.PostingRequest;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P13: Redis rate limiting. Every request here is a valid transfer from a funded wallet, so the
 * only way to get anything but 201 is the limiter itself (the old version posted "{}", which is
 * a correct 400, and then expected 200). Each test uses a fresh actor id, so tests don't share
 * a window.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "escrow.ratelimit.enabled=true") // the base turns it off for everyone else
@DisplayName("Rate limiter")
class RateLimiterIT extends AbstractIntegrationTest {

    private static final int DEFAULT_LIMIT = 30;
    private static final int PAYOUT_LIMIT = 5;

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private TransactionTemplate tx;

    private String actor;
    private Account from;
    private Account to;

    @BeforeEach
    void setUp() {
        // The limiter counts in fixed 60 s windows of the injected clock. On real time a test could
        // straddle a window boundary and split its requests over two windows: CI once saw payout
        // request 1 at 18:38:59.65 and 2..6 after 18:39:00 (X-RateLimit-Remaining 4,4,3,2,1,0), so
        // the 6th request got through. Freeze the clock mid-window; only advance() moves it.
        long now = clock.instant().getEpochSecond();
        clock.freezeAt(Instant.ofEpochSecond((now / 60) * 60 + 30));
        String run = UUID.randomUUID().toString().substring(0, 8);
        // A fresh ADMIN per test (own rate-limit window); actor ids must be real users now.
        actor = String.valueOf(newAdmin().id());
        from = wallet("RL_FROM:" + run, 1_000_000);
        to = wallet("RL_TO:" + run, 0);
    }

    @Test
    @DisplayName("N requests pass with rate-limit headers; request N+1 is 429 RATE_LIMITED with Retry-After")
    void limitIsHitAtNPlusOne() throws Exception {
        for (int i = 1; i <= DEFAULT_LIMIT; i++) {
            transfer(actor).andExpect(status().isCreated())
                .andExpect(header().string("X-RateLimit-Limit", String.valueOf(DEFAULT_LIMIT)))
                .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(DEFAULT_LIMIT - i)));
        }
        transfer(actor)
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        assertThat(balance(to)).as("the rejected request moved no money").isEqualTo(DEFAULT_LIMIT * 10L);
    }

    @Test
    @DisplayName("the window resets once the clock moves past it")
    void windowResetsAfterTheClockAdvances() throws Exception {
        for (int i = 0; i < DEFAULT_LIMIT; i++) {
            transfer(actor).andExpect(status().isCreated());
        }
        transfer(actor).andExpect(status().isTooManyRequests());

        clock.advance(Duration.ofSeconds(61));
        transfer(actor).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("each actor has its own budget")
    void actorsHaveIndependentLimits() throws Exception {
        for (int i = 0; i < DEFAULT_LIMIT; i++) {
            transfer(actor).andExpect(status().isCreated());
        }
        transfer(actor).andExpect(status().isTooManyRequests());
        transfer(String.valueOf(newAdmin().id())).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("payout requests have the stricter limit")
    void payoutsHaveTheStricterLimit() throws Exception {
        // Freelancer wallets start empty, so these are 422 INSUFFICIENT_FUNDS: what matters here
        // is that requests 1..5 get past the limiter and request 6 does not.
        // A real freelancer: the users trigger creates their (empty) wallet.
        String freelancer = newFreelancer();
        for (int i = 0; i < PAYOUT_LIMIT; i++) {
            payout(freelancer).andExpect(status().isUnprocessableEntity());
        }
        payout(freelancer).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("fixed windows: a burst that straddles a window boundary is counted in two windows")
    void aBurstAcrossAWindowBoundaryIsCountedInTwoWindows() throws Exception {
        // What the old tests ran into by accident on real time, pinned down on a frozen clock.
        long now = clock.instant().getEpochSecond();
        clock.freezeAt(Instant.ofEpochSecond((now / 60) * 60 + 59));   // last second of a window
        String freelancer = newFreelancer();
        payout(freelancer).andExpect(status().isUnprocessableEntity())
            .andExpect(header().string("X-RateLimit-Remaining", "4"));
        clock.advance(Duration.ofSeconds(1));                             // next window
        for (int i = PAYOUT_LIMIT - 1; i >= 0; i--) {
            payout(freelancer).andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(i)));
        }
        payout(freelancer).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("requests without X-Actor-Id are not limited (they are rejected as 401 instead)")
    void requestsWithoutActorAreNotLimited() throws Exception {
        // Transfers are ADMIN-only now, so an anonymous request is 401 UNAUTHENTICATED; the point
        // here is that request N+1 is still 401, never 429, and moves no money.
        for (int i = 0; i < DEFAULT_LIMIT + 1; i++) {
            mockMvc.perform(post("/api/v1/transfers")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(transferBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().doesNotExist("X-RateLimit-Limit"));
        }
        assertThat(balance(to)).isZero();
    }

    @Test
    @DisplayName("admin endpoints are not limited")
    void adminEndpointsAreNotLimited() throws Exception {
        for (int i = 0; i < DEFAULT_LIMIT + 1; i++) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/v1/admin/invariants").header("X-Actor-Id", actor).header("X-Actor-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-RateLimit-Limit"));
        }
    }

    @Test
    @DisplayName("with Redis down, requests still succeed (fail open)")
    void failsOpenWhenRedisIsDown() throws Exception {
        pauseRedis();
        try {
            for (int i = 0; i < DEFAULT_LIMIT + 1; i++) {
                transfer(actor).andExpect(status().isCreated());
            }
        } finally {
            unpauseRedis();
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    private ResultActions transfer(String actorId) throws Exception {
        return mockMvc.perform(post("/api/v1/transfers")
            .header("X-Actor-Id", actorId)
            .header("X-Actor-Role", "ADMIN")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(transferBody()));
    }

    /** A real freelancer: the users trigger creates their (empty) wallet. */
    private String newFreelancer() {
        return String.valueOf(jdbcClient.sql(
                "INSERT INTO users (name, role) VALUES ('rl-freelancer', 'FREELANCER') RETURNING id")
            .query(Long.class).single());
    }

    private ResultActions payout(String actorId) throws Exception {
        return mockMvc.perform(post("/api/v1/payouts")
            .header("X-Actor-Id", actorId)
            .header("X-Actor-Role", "FREELANCER")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amountPaise\":100}"));
    }

    private String transferBody() {
        return "{\"fromAccountId\":" + from.id() + ",\"toAccountId\":" + to.id() + ",\"amountPaise\":10,\"memo\":\"rl\"}";
    }

    private Account wallet(String code, long amount) {
        Account acc = accountRepository.create(code, AccountType.CLIENT_WALLET, null, false);
        if (amount > 0) {
            long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();
            tx.executeWithoutResult(s -> ledgerService.post(new PostingRequest("TEST_SEED", "TEST_SEED", code,
                "rl-seed:" + code, "seed", List.of(new Leg(bank, -amount), new Leg(acc.id(), amount)))));
        }
        return acc;
    }

    private long balance(Account acc) {
        return accountRepository.findById(acc.id()).orElseThrow().balance();
    }
}
