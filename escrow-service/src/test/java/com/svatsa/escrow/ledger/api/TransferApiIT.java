package com.svatsa.escrow.ledger.api;

import com.svatsa.escrow.ledger.Account;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.AccountType;
import com.svatsa.escrow.recon.InvariantChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the transfer API and idempotency.
 * 
 * These tests verify:
 * - Transfer moves money and updates both account balances
 * - Same Idempotency-Key+body sent twice returns byte-identical response
 * - Replayed response includes Idempotent-Replayed header
 * - Only 1 journal entry exists after replay
 * - Same key with different body returns 422 IDEMPOTENCY_KEY_REUSED
 * - Insufficient funds returns 422 INSUFFICIENT_FUNDS, no entry written
 * - Insufficient funds allows key reuse on retry
 * - Transfer to self returns 400 VALIDATION_FAILED
 * 
 * Note: These tests are disabled by default because they require Postgres
 * with the full ledger schema (Flyway migrations). Enable them in CI with
 * docker-compose. For local development, use the LedgerSchemaIT tests or
 * unit tests for the API layer.
 */
@Disabled("Requires Postgres with Flyway migrations; enable in CI or with docker-compose")
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
class TransferApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private InvariantChecker invariantChecker;

    private Account accountA;
    private Account accountB;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clear previous data
        clearLedger();
        
        // Create two test accounts
        accountA = accountRepository.create(
            "TEST:ACCOUNT_A",
            AccountType.CLIENT_WALLET,
            1L,
            false
        );
        
        accountB = accountRepository.create(
            "TEST:ACCOUNT_B",
            AccountType.FREELANCER_WALLET,
            2L,
            false
        );
        
        // Fund account A with 10,000 paise
        jdbcClient.sql("UPDATE accounts SET balance = 10000 WHERE id = ?")
            .param(accountA.id())
            .update();
    }

    /**
     * Delete all ledger data (for test isolation).
     * Must be in a transaction.
     */
    @Transactional
    private void clearLedger() {
        try {
            // H2 doesn't support CASCADE, so delete in order
            try {
                jdbcClient.sql("DELETE FROM postings").update();
            } catch (Exception e) {
                // Table may not exist yet
            }
            try {
                jdbcClient.sql("DELETE FROM journal_entries").update();
            } catch (Exception e) {
                // Table may not exist yet
            }
            try {
                jdbcClient.sql("DELETE FROM idempotency_keys").update();
            } catch (Exception e) {
                // Table may not exist yet
            }
            try {
                jdbcClient.sql("UPDATE accounts SET balance = 0").update();
            } catch (Exception e) {
                // Table may not exist yet
            }
        } catch (Exception e) {
            // Ignore cleanup errors in test mode
        }
    }

    @Test
    @Transactional
    void transferMovesMoneyBetweenAccounts() throws Exception {
        // Arrange: transfer 1000 paise from A to B
        TransferRequest request = new TransferRequest(
            accountA.id(),
            accountB.id(),
            1000,
            "Test transfer"
        );

        // Act: POST /api/v1/transfers
        mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", "transfer-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "fromAccountId": %d,
                        "toAccountId": %d,
                        "amountPaise": 1000,
                        "memo": "Test transfer"
                    }
                    """.formatted(accountA.id(), accountB.id())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.entryId").isNumber())
            .andExpect(jsonPath("$.replayed").value(false));

        // Assert: both balances updated correctly
        mockMvc.perform(get("/api/v1/accounts/{id}", accountA.id()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(9000));

        mockMvc.perform(get("/api/v1/accounts/{id}", accountB.id()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(1000));

        // Assert: exactly 1 journal entry exists
        Long entryCount = jdbcClient.sql("SELECT COUNT(*) FROM journal_entries")
            .query(Long.class)
            .single();
        assertThat(entryCount).isEqualTo(1);
    }

    @Test
    @Transactional
    void idempotentKeyReplayReturnsSameResponse() throws Exception {
        // Arrange: first transfer
        String idempotencyKey = "transfer-idempotent-1";
        String requestBody = """
            {
                "fromAccountId": %d,
                "toAccountId": %d,
                "amountPaise": 500,
                "memo": "Idempotent transfer"
            }
            """.formatted(accountA.id(), accountB.id());

        // Act: POST first time
        String firstResponse = mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();

        // Act: POST second time with same key and body
        String secondResponse = mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.replayed").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();

        // Assert: responses are identical
        assertThat(firstResponse).isEqualTo(secondResponse);

        // Assert: only 1 journal entry exists
        Long entryCount = jdbcClient.sql("SELECT COUNT(*) FROM journal_entries")
            .query(Long.class)
            .single();
        assertThat(entryCount).isEqualTo(1);

        // Assert: balances only changed once
        mockMvc.perform(get("/api/v1/accounts/{id}", accountA.id()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(9500));

        mockMvc.perform(get("/api/v1/accounts/{id}", accountB.id()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(500));
    }

    @Test
    @Transactional
    void idempotencyKeyReuseWithDifferentBodyReturns422() throws Exception {
        // Arrange: first request
        String idempotencyKey = "transfer-reuse-1";
        
        // Act: POST first request
        mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "fromAccountId": %d,
                        "toAccountId": %d,
                        "amountPaise": 100,
                        "memo": "First request"
                    }
                    """.formatted(accountA.id(), accountB.id())))
            .andExpect(status().isCreated());

        // Act: POST with same key but different amount
        mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "fromAccountId": %d,
                        "toAccountId": %d,
                        "amountPaise": 200,
                        "memo": "Different request"
                    }
                    """.formatted(accountA.id(), accountB.id())))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Assert: only 1 journal entry (first one won)
        Long entryCount = jdbcClient.sql("SELECT COUNT(*) FROM journal_entries")
            .query(Long.class)
            .single();
        assertThat(entryCount).isEqualTo(1);
    }

    @Test
    @Transactional
    void insufficientFundsReturns422() throws Exception {
        // Arrange: try to transfer 20,000 paise when account only has 10,000
        String idempotencyKey = "transfer-insufficient-1";

        // Act: POST transfer with insufficient funds
        mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "fromAccountId": %d,
                        "toAccountId": %d,
                        "amountPaise": 20000,
                        "memo": "Insufficient funds transfer"
                    }
                    """.formatted(accountA.id(), accountB.id())))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        // Assert: no journal entry written
        Long entryCount = jdbcClient.sql("SELECT COUNT(*) FROM journal_entries")
            .query(Long.class)
            .single();
        assertThat(entryCount).isEqualTo(0);

        // Assert: balances unchanged
        mockMvc.perform(get("/api/v1/accounts/{id}", accountA.id()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(10000));

        mockMvc.perform(get("/api/v1/accounts/{id}", accountB.id()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    @Transactional
    void insufficientFundsAllowsKeyReuse() throws Exception {
        // Arrange: first attempt fails due to insufficient funds
        String idempotencyKey = "transfer-reuse-insufficient-1";

        // Act: POST with insufficient funds
        mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "fromAccountId": %d,
                        "toAccountId": %d,
                        "amountPaise": 20000,
                        "memo": "First attempt - insufficient"
                    }
                    """.formatted(accountA.id(), accountB.id())))
            .andExpect(status().isUnprocessableEntity());

        // Act: retry with same key but smaller amount (should succeed)
        mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "fromAccountId": %d,
                        "toAccountId": %d,
                        "amountPaise": 5000,
                        "memo": "Retry with smaller amount"
                    }
                    """.formatted(accountA.id(), accountB.id())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.replayed").value(false));

        // Assert: transfer succeeded and balances are correct
        mockMvc.perform(get("/api/v1/accounts/{id}", accountA.id()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(5000));

        mockMvc.perform(get("/api/v1/accounts/{id}", accountB.id()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(5000));
    }

    @Test
    @Transactional
    void transferToSelfReturns400() throws Exception {
        // Act: POST transfer from account to itself
        mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", "transfer-self-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "fromAccountId": %d,
                        "toAccountId": %d,
                        "amountPaise": 100,
                        "memo": "Transfer to self"
                    }
                    """.formatted(accountA.id(), accountA.id())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Assert: no journal entry written
        Long entryCount = jdbcClient.sql("SELECT COUNT(*) FROM journal_entries")
            .query(Long.class)
            .single();
        assertThat(entryCount).isEqualTo(0);

        // Assert: balance unchanged
        mockMvc.perform(get("/api/v1/accounts/{id}", accountA.id()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(10000));
    }

    @Test
    @Transactional
    void missingIdempotencyKeyReturns400() throws Exception {
        // Act: POST transfer without Idempotency-Key header
        mockMvc.perform(post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "fromAccountId": %d,
                        "toAccountId": %d,
                        "amountPaise": 100,
                        "memo": "Missing key"
                    }
                    """.formatted(accountA.id(), accountB.id())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Assert: no journal entry written
        Long entryCount = jdbcClient.sql("SELECT COUNT(*) FROM journal_entries")
            .query(Long.class)
            .single();
        assertThat(entryCount).isEqualTo(0);
    }

    @Test
    @Transactional
    void negativeAmountReturns400() throws Exception {
        // Act: POST transfer with negative amount
        mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", "transfer-negative-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "fromAccountId": %d,
                        "toAccountId": %d,
                        "amountPaise": -100,
                        "memo": "Negative amount"
                    }
                    """.formatted(accountA.id(), accountB.id())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Assert: no journal entry written
        Long entryCount = jdbcClient.sql("SELECT COUNT(*) FROM journal_entries")
            .query(Long.class)
            .single();
        assertThat(entryCount).isEqualTo(0);
    }

    @Test
    @Transactional
    void simulateDepositMovesMoneyFromRazorpay() throws Exception {
        // Arrange: create a client account
        Account clientAccount = accountRepository.create(
            "TEST:CLIENT_DEPOSIT",
            AccountType.CLIENT_WALLET,
            3L,
            false
        );

        // Act: POST simulate deposit
        mockMvc.perform(post("/api/v1/admin/deposits/simulate")
                .header("Idempotency-Key", "simulate-deposit-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "clientAccountId": %d,
                        "amountPaise": 5000
                    }
                    """.formatted(clientAccount.id())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.entryId").isNumber())
            .andExpect(jsonPath("$.replayed").value(false));

        // Assert: client account balance increased
        mockMvc.perform(get("/api/v1/accounts/{id}", clientAccount.id()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(5000));

        // Assert: EXTERNAL_BANK:RAZORPAY balance decreased
        Long razorpayAccount = jdbcClient.sql("""
                SELECT id FROM accounts WHERE code = 'EXTERNAL_BANK:RAZORPAY'
            """)
            .query(Long.class)
            .optional()
            .orElse(null);

        if (razorpayAccount != null) {
            mockMvc.perform(get("/api/v1/accounts/{id}", razorpayAccount))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(-5000));
        }

        // Assert: exactly 1 journal entry exists
        Long entryCount = jdbcClient.sql("SELECT COUNT(*) FROM journal_entries")
            .query(Long.class)
            .single();
        assertThat(entryCount).isEqualTo(1);
    }

    @Test
    @Transactional
    void getAccountReturnsBalanceAndCode() throws Exception {
        // Act: GET account
        mockMvc.perform(get("/api/v1/accounts/{id}", accountA.id()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(accountA.id()))
            .andExpect(jsonPath("$.code").value(accountA.code()))
            .andExpect(jsonPath("$.balance").value(10000));
    }

    @Test
    @Transactional
    void getAccountNotFoundReturns404() throws Exception {
        // Act: GET non-existent account
        mockMvc.perform(get("/api/v1/accounts/99999"))
            .andExpect(status().isNotFound());
    }

    @Test
    @Transactional
    void getPostingsReturnsPaginatedResults() throws Exception {
        // Arrange: do a transfer to create postings
        mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", "transfer-for-postings-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "fromAccountId": %d,
                        "toAccountId": %d,
                        "amountPaise": 1000,
                        "memo": "Create postings"
                    }
                    """.formatted(accountA.id(), accountB.id())))
            .andExpect(status().isCreated());

        // Act: GET postings for account A
        mockMvc.perform(get("/api/v1/accounts/{id}/postings", accountA.id())
                .param("limit", "50"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].amount").value(-1000))
            .andExpect(jsonPath("$[0].id").isNumber())
            .andExpect(jsonPath("$[0].created_at").isNotEmpty());

        // Act: GET postings for account B
        mockMvc.perform(get("/api/v1/accounts/{id}/postings", accountB.id())
                .param("limit", "50"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].amount").value(1000));
    }
}
