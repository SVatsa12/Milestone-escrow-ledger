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
        String run = UUID.randomUUID().toString().substring(0, 8);
        actor = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
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
        transfer(actor + "0").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("payout requests have the stricter limit")
    void payoutsHaveTheStricterLimit() throws Exception {
        // Freelancer wallets start empty, so these are 422 INSUFFICIENT_FUNDS: what matters here
        // is that requests 1..5 get past the limiter and request 6 does not.
        long freelancer = Long.parseLong(actor);
        accountRepository.create("FREELANCER_WALLET:" + freelancer, AccountType.FREELANCER_WALLET, freelancer, false);
        for (int i = 0; i < PAYOUT_LIMIT; i++) {
            payout(actor).andExpect(status().isUnprocessableEntity());
        }
        payout(actor).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("requests without X-Actor-Id are not limited")
    void requestsWithoutActorAreNotLimited() throws Exception {
        for (int i = 0; i < DEFAULT_LIMIT + 1; i++) {
            mockMvc.perform(post("/api/v1/transfers")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(transferBody()))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("X-RateLimit-Limit"));
        }
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
            tx.executeWithoutResult(s -> ledgerService.post(new PostingRequest("DEPOSIT", "TEST_SEED", code,
                "rl-seed:" + code, "seed", List.of(new Leg(bank, -amount), new Leg(acc.id(), amount)))));
        }
        return acc;
    }

    private long balance(Account acc) {
        return accountRepository.findById(acc.id()).orElseThrow().balance();
    }
}
