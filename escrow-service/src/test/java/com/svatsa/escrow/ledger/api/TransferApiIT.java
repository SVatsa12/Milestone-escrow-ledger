package com.svatsa.escrow.ledger.api;

import com.svatsa.escrow.ledger.Account;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.AccountType;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.PostingRequest;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P3: transfer API and HTTP idempotency, against real Postgres.
 * Each test funds fresh wallets through the ledger and counts only its own journal entries,
 * so it holds on the shared database and leaves the invariants intact.
 */
@AutoConfigureMockMvc
class TransferApiIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private TransactionTemplate tx;

    private String run;
    private Account a;
    private Account b;

    @BeforeEach
    void setUp() {
        run = UUID.randomUUID().toString().substring(0, 8);
        a = wallet("A", 10_000);
        b = wallet("B", 0);
    }

    @Test
    void transferMovesMoneyBetweenAccounts() throws Exception {
        transfer(key("move"), a, b, 1_000)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.entryId").isNumber());

        assertThat(balance(a)).isEqualTo(9_000);
        assertThat(balance(b)).isEqualTo(1_000);
        assertThat(entries(key("move"))).isEqualTo(1);
    }

    @Test
    void sameKeyAndBodyReplaysTheFirstResponseByteForByte() throws Exception {
        String key = key("replay");
        MvcResult first = transfer(key, a, b, 500).andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotent-Replayed")).andReturn();
        MvcResult second = transfer(key, a, b, 500).andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true")).andReturn();

        assertThat(second.getResponse().getContentAsString())
            .isEqualTo(first.getResponse().getContentAsString());
        assertThat(entries(key)).isEqualTo(1);
        assertThat(balance(a)).isEqualTo(9_500);
        assertThat(balance(b)).isEqualTo(500);
    }

    @Test
    void sameKeyWithDifferentBodyIs422IdempotencyKeyReused() throws Exception {
        String key = key("reuse");
        transfer(key, a, b, 100).andExpect(status().isCreated());

        transfer(key, a, b, 999)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(entries(key)).isEqualTo(1);
        assertThat(balance(b)).isEqualTo(100);
    }

    @Test
    void insufficientFundsIs422WritesNothingAndTheKeyCanBeReused() throws Exception {
        String key = key("insufficient");
        transfer(key, a, b, 50_000)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        assertThat(entries(key)).isZero();
        assertThat(balance(a)).isEqualTo(10_000);

        // The rejected request stored nothing, so the same key works for a valid request later.
        transfer(key, a, b, 5_000).andExpect(status().isCreated());
        assertThat(entries(key)).isEqualTo(1);
        assertThat(balance(a)).isEqualTo(5_000);
        assertThat(balance(b)).isEqualTo(5_000);
    }

    @Test
    void transferToSelfIs400() throws Exception {
        transfer(key("self"), a, a, 100)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(entries(key("self"))).isZero();
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                .content(body(a, b, 100)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(balance(a)).isEqualTo(10_000);
    }

    @Test
    void nonPositiveAmountIs400() throws Exception {
        transfer(key("negative"), a, b, -100)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(entries(key("negative"))).isZero();
    }

    @Test
    void simulatedDepositMovesMoneyFromRazorpay() throws Exception {
        long razorpayBefore = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().balance();

        mockMvc.perform(post("/api/v1/admin/deposits/simulate")
                .header("Idempotency-Key", key("deposit"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientAccountId\":" + b.id() + ",\"amountPaise\":5000}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.entryId").isNumber());

        assertThat(balance(b)).isEqualTo(5_000);
        assertThat(accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().balance())
            .isEqualTo(razorpayBefore - 5_000);
    }

    @Test
    void getAccountReturnsIdCodeAndBalance() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}", a.id()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(a.id()))
            .andExpect(jsonPath("$.code").value(a.code()))
            .andExpect(jsonPath("$.balance").value(10_000));
    }

    @Test
    void unknownAccountIs404NotFound() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}", 999_999_999L))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void postingsAreListedNewestFirst() throws Exception {
        transfer(key("postings"), a, b, 1_000).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/accounts/{id}/postings", a.id()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].amount").value(-1_000))   // the transfer
            .andExpect(jsonPath("$[1].amount").value(10_000));  // the seed deposit
        mockMvc.perform(get("/api/v1/accounts/{id}/postings", b.id()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].amount").value(1_000));
    }

    // --- helpers -----------------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions transfer(String key, Account from, Account to, long amount)
            throws Exception {
        return mockMvc.perform(post("/api/v1/transfers")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(from, to, amount)));
    }

    private static String body(Account from, Account to, long amount) {
        return "{\"fromAccountId\":" + from.id() + ",\"toAccountId\":" + to.id()
            + ",\"amountPaise\":" + amount + ",\"memo\":\"test\"}";
    }

    private String key(String name) {
        return "tapi-" + name + "-" + run;
    }

    private long entries(String key) {
        return jdbcClient.sql("SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = ?")
            .param("transfer:" + key).query(Long.class).single();
    }

    private Account wallet(String name, long amount) {
        Account acc = accountRepository.create("TAPI:" + name + ":" + run, AccountType.CLIENT_WALLET, null, false);
        if (amount > 0) {
            long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();
            tx.executeWithoutResult(s -> ledgerService.post(new PostingRequest("DEPOSIT", "TEST_SEED", acc.code(),
                "tapi-seed:" + acc.code(), "seed", List.of(new Leg(bank, -amount), new Leg(acc.id(), amount)))));
        }
        return acc;
    }

    private long balance(Account acc) {
        return accountRepository.findById(acc.id()).orElseThrow().balance();
    }
}
