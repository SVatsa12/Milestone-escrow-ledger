package com.svatsa.escrow.escrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.svatsa.escrow.chaos.AbstractToxiproxyIT;
import com.svatsa.escrow.test.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.svatsa.escrow.test.ApiClient.actor;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for project creation on real Postgres (chaos/FINDINGS.md #8 and #9).
 */
@DisplayName("Project API on Postgres")
class ProjectApiIT extends AbstractToxiproxyIT {

    private ApiClient api;

    @BeforeEach
    void client() {
        api = new ApiClient(serverPort);
    }

    @Test
    @DisplayName("a project is created and read back with its review window and milestones")
    void createAndReadProject() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        long client = api.createUser("pc-" + run, "CLIENT");
        long freelancer = api.createUser("pf-" + run, "FREELANCER");

        // review_window (INTERVAL) used to be bound as varchar, so this was always a 500.
        JsonNode created = api.expect(api.post("/api/escrow/projects", null, actor(client, "CLIENT"),
            ApiClient.projectBody(client, freelancer, "p-" + run, 10_000, 20_000)), 201);
        long projectId = created.path("id").asLong();

        // ...and reading it back had no INTERVAL -> Duration converter.
        JsonNode read = api.expect(api.get("/api/escrow/projects/" + projectId), 200);
        assertThat(read.path("project").path("reviewWindow").asText()).isEqualTo("PT120H");
        assertThat(read.path("milestones").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("projects created concurrently each get their own ESCROW and DISPUTE_HOLD accounts")
    void concurrentProjectsDoNotShareAccounts() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        long client = api.createUser("cc-" + run, "CLIENT");
        long freelancer = api.createUser("cf-" + run, "FREELANCER");
        String body = ApiClient.projectBody(client, freelancer, "c-" + run, 100);

        int n = 30;
        List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            calls.add(api.http().sendAsync(api.postRequest("/api/escrow/projects", null, actor(client, "CLIENT"), body).build(),
                HttpResponse.BodyHandlers.ofString()));
        }
        Set<Long> escrowAccounts = new HashSet<>();
        Set<Long> holdAccounts = new HashSet<>();
        for (var call : calls) {
            JsonNode p = api.expect(call.get(), 201);
            long id = p.path("id").asLong();
            escrowAccounts.add(p.path("escrowAccountId").asLong());
            holdAccounts.add(p.path("disputeHoldAccountId").asLong());
            assertThat(jdbcClient.sql("SELECT code FROM accounts WHERE id = ?").param(p.path("escrowAccountId").asLong())
                .query(String.class).single()).isEqualTo("ESCROW:" + id);
        }
        // Codes used to be "ESCROW:" + currentTimeMillis(), so same-millisecond projects shared accounts.
        assertThat(escrowAccounts).hasSize(n);
        assertThat(holdAccounts).hasSize(n);
    }
}
