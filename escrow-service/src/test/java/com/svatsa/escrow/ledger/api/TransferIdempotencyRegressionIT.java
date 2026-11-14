package com.svatsa.escrow.ledger.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.svatsa.escrow.chaos.AbstractToxiproxyIT;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.AccountType;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.PostingRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests (default build) for bugs that only showed up once the chaos suite ran the
 * app against real Postgres. See chaos/FINDINGS.md.
 */
@DisplayName("Transfer API regressions found by chaos")
class TransferIdempotencyRegressionIT extends AbstractToxiproxyIT {

    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private TransactionTemplate tx;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("an account created outside a transaction is persisted")
    void writesOutsideTransactionsAreNotRolledBack() {
        String code = "CLIENT_WALLET:" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
        accountRepository.create(code, AccountType.CLIENT_WALLET, 1L, false);

        Long count = jdbcClient.sql("SELECT count(*) FROM accounts WHERE code = ?").param(code).query(Long.class).single();
        assertThat(count).as("hikari auto-commit=false silently rolled this insert back").isEqualTo(1);
    }

    @Test
    @DisplayName("a transfer succeeds, and a retry with the same key replays the stored response")
    void transferThenReplay() throws Exception {
        long from = fundedWallet(10_000);
        long to = fundedWallet(0);
        String key = "reg-transfer-" + UUID.randomUUID();
        String body = "{\"fromAccountId\":" + from + ",\"toAccountId\":" + to + ",\"amountPaise\":700,\"memo\":\"reg\"}";

        HttpResponse<String> first = post(key, body);
        HttpResponse<String> retry = post(key, body);

        // Storing the response into the JSONB column used to fail, so every transfer was a 500.
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(retry.statusCode()).as(retry.body()).isEqualTo(201);
        assertThat(first.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        JsonNode a = json.readTree(first.body());
        JsonNode b = json.readTree(retry.body());
        assertThat(b.path("entryId").asLong()).isEqualTo(a.path("entryId").asLong());
        assertThat(accountRepository.findById(to).orElseThrow().balance()).isEqualTo(700);
        assertThat(jdbcClient.sql("SELECT count(*) FROM journal_entries WHERE idempotency_key = ?")
            .param("transfer:" + key).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("a retry with the same key but a different body is rejected and moves nothing")
    void sameKeyDifferentBodyIsRejected() throws Exception {
        long from = fundedWallet(10_000);
        long to = fundedWallet(0);
        String key = "reg-reuse-" + UUID.randomUUID();

        assertThat(post(key, "{\"fromAccountId\":" + from + ",\"toAccountId\":" + to + ",\"amountPaise\":100,\"memo\":\"a\"}")
            .statusCode()).isEqualTo(201);
        HttpResponse<String> reused = post(key,
            "{\"fromAccountId\":" + from + ",\"toAccountId\":" + to + ",\"amountPaise\":999,\"memo\":\"a\"}");

        assertThat(reused.statusCode()).isBetween(400, 499);
        assertThat(accountRepository.findById(to).orElseThrow().balance()).isEqualTo(100);
    }

    private HttpResponse<String> post(String key, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + "/api/v1/transfers"))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key)
            .header("X-Actor-Id", "1")
            .header("X-Actor-Role", "ADMIN")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private long fundedWallet(long amount) {
        long ownerId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
        long id = accountRepository.create("CLIENT_WALLET:" + ownerId, AccountType.CLIENT_WALLET, ownerId, false).id();
        if (amount > 0) {
            long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();
            tx.executeWithoutResult(s -> ledgerService.post(new PostingRequest(
                "TEST_SEED", "TEST_SEED", String.valueOf(id), "reg-seed:" + UUID.randomUUID(), "seed",
                List.of(new Leg(bank, -amount), new Leg(id, amount)))));
        }
        return id;
    }
}
