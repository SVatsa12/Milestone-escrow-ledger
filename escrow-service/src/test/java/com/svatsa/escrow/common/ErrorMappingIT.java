package com.svatsa.escrow.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.svatsa.escrow.chaos.AbstractToxiproxyIT;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.PostingRequest;
import com.svatsa.escrow.test.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static com.svatsa.escrow.test.ApiClient.actor;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * State-machine conflicts and unknown ids are client errors with stable codes, not 500s
 * (chaos/FINDINGS.md #10). The load tests count every 5xx as a failure.
 */
@DisplayName("Error mapping for state conflicts and unknown ids")
class ErrorMappingIT extends AbstractToxiproxyIT {

    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private TransactionTemplate tx;

    private ApiClient api;

    @BeforeEach
    void client() {
        api = new ApiClient(serverPort);
    }

    @Test
    @DisplayName("approving twice is 409 INVALID_STATE_TRANSITION; an unknown milestone is 404 NOT_FOUND")
    void conflictsAre409AndUnknownIdsAre404() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        long client = api.createUser("ec-" + run, "CLIENT");
        long freelancer = api.createUser("ef-" + run, "FREELANCER");
        fund("CLIENT_WALLET:" + client, 100_000);

        long projectId = api.expect(api.post("/api/v1/projects", null, actor(client, "CLIENT"),
            ApiClient.projectBody(client, freelancer, "e-" + run, 10_000)), 201).path("id").asLong();
        api.expect(api.post("/api/v1/projects/" + projectId + "/fund", "efund-" + run, actor(client, "CLIENT"), ""), 200);
        long milestoneId = api.expect(api.get("/api/v1/projects/" + projectId), 200)
            .path("milestones").get(0).path("id").asLong();
        api.expect(api.post("/api/v1/milestones/" + milestoneId + "/submit", "esub-" + run, actor(freelancer, "FREELANCER"), ""), 200);
        api.expect(api.post("/api/v1/milestones/" + milestoneId + "/approve", "eappr-" + run, actor(client, "CLIENT"), ""), 200);

        // Used to fall through to the generic handler as 500 INTERNAL_ERROR.
        JsonNode again = api.expect(api.post("/api/v1/milestones/" + milestoneId + "/approve",
            "eappr2-" + run, actor(client, "CLIENT"), ""), 409);
        assertThat(again.path("code").asText()).isEqualTo("INVALID_STATE_TRANSITION");

        JsonNode missing = api.expect(api.post("/api/v1/milestones/999999999/approve",
            "eappr3-" + run, actor(client, "CLIENT"), ""), 404);
        assertThat(missing.path("code").asText()).isEqualTo("NOT_FOUND");
    }

    private void fund(String walletCode, long amount) {
        long wallet = accountRepository.findByCode(walletCode).orElseThrow().id();
        long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();
        tx.executeWithoutResult(s -> ledgerService.post(new PostingRequest(
            "TEST_SEED", "TEST_SEED", walletCode, "err-seed:" + UUID.randomUUID(), "seed",
            List.of(new Leg(bank, -amount), new Leg(wallet, amount)))));
    }
}
