package com.svatsa.escrow;

import com.svatsa.escrow.test.ProviderControl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.svatsa.escrow.chaos.AbstractToxiproxyIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Walks every HTTP call the k6 load scripts (load/) make, in order, against real Postgres:
 * if this passes, setup() and the scenarios can run. Ends with GET /api/v1/admin/invariants.
 */
@DisplayName("HTTP paths used by the load tests")
class LoadPathApiIT extends AbstractToxiproxyIT {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("setup, transfer, approve, dispute, payout, reads, invariants")
    void fullLoadPath() throws Exception {
        PROVIDER.setMode(ProviderControl.Mode.SUCCESS);
        String run = UUID.randomUUID().toString().substring(0, 8);

        // Users: wallets are created by a DB trigger, found by code.
        long client = createUser("client-" + run, "CLIENT");
        long client2 = createUser("client2-" + run, "CLIENT");
        long freelancer = createUser("freelancer-" + run, "FREELANCER");
        long clientWallet = accountId("CLIENT_WALLET:" + client);
        long client2Wallet = accountId("CLIENT_WALLET:" + client2);
        long freelancerWallet = accountId("FREELANCER_WALLET:" + freelancer);

        // Simulated deposits.
        expect(post("/api/v1/admin/deposits/simulate", "dep-" + run, admin(),
            "{\"clientAccountId\":" + clientWallet + ",\"amountPaise\":1000000}"), 201);
        expect(post("/api/v1/admin/deposits/simulate", "dep2-" + run, admin(),
            "{\"clientAccountId\":" + client2Wallet + ",\"amountPaise\":1000000}"), 201);

        // Project with 3 milestones, funded and submitted.
        String due = Instant.now().plus(30, ChronoUnit.DAYS).toString();
        JsonNode project = expect(post("/api/v1/projects", null, actor(client, "CLIENT"),
            "{\"clientId\":" + client + ",\"freelancerId\":" + freelancer + ",\"title\":\"p-" + run + "\","
                + "\"reviewWindow\":\"P5D\",\"milestones\":["
                + "{\"title\":\"m1\",\"amountPaise\":10000,\"dueAt\":\"" + due + "\"},"
                + "{\"title\":\"m2\",\"amountPaise\":20000,\"dueAt\":\"" + due + "\"},"
                + "{\"title\":\"m3\",\"amountPaise\":30000,\"dueAt\":\"" + due + "\"}]}"), 201);
        long projectId = project.path("id").asLong();
        expect(post("/api/v1/projects/" + projectId + "/fund", "fund-" + run, actor(client, "CLIENT"), ""), 200);

        JsonNode withMilestones = expect(get("/api/v1/projects/" + projectId), 200);
        JsonNode milestones = withMilestones.path("milestones");
        assertThat(milestones.size()).isEqualTo(3);
        long[] ids = new long[3];
        for (int i = 0; i < 3; i++) {
            ids[i] = milestones.get(i).path("id").asLong();
            expect(post("/api/v1/milestones/" + ids[i] + "/submit", "sub-" + run + i, actor(freelancer, "FREELANCER"), ""), 200);
        }

        // Scenario calls.
        expect(post("/api/v1/transfers", "tr-" + run, admin(),
            "{\"fromAccountId\":" + clientWallet + ",\"toAccountId\":" + client2Wallet + ",\"amountPaise\":500,\"memo\":\"load\"}"), 201);
        expect(post("/api/v1/milestones/" + ids[0] + "/approve", "appr-" + run, actor(client, "CLIENT"), ""), 200);
        expect(post("/api/v1/disputes", "disp-" + run, actor(client, "CLIENT"),
            "{\"milestoneId\":" + ids[1] + ",\"reason\":\"load\"}"), 201);
        JsonNode payout = expect(post("/api/v1/payouts", "po-" + run, actor(freelancer, "FREELANCER"),
            "{\"amountPaise\":1000}"), 202);
        assertThat(payout.path("state").asText()).isEqualTo("CONFIRMED");

        // Conflicts and unknown ids are client errors, not 500s (the load tests treat 5xx as failures).
        JsonNode again = expect(post("/api/v1/milestones/" + ids[0] + "/approve", "appr2-" + run, actor(client, "CLIENT"), ""), 409);
        assertThat(again.path("code").asText()).isEqualTo("INVALID_STATE_TRANSITION");
        expect(post("/api/v1/milestones/999999999/approve", "appr3-" + run, actor(client, "CLIENT"), ""), 404);

        expect(get("/api/v1/accounts/" + freelancerWallet), 200);
        JsonNode invariants = expect(get("/api/v1/admin/invariants", admin()), 200);
        assertThat(invariants.path("violations_count").asInt()).as(invariants.toString()).isZero();

        // Outbox -> Kafka -> audit consumer works (it used to crash-loop on deserialization).
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(60)).untilAsserted(() ->
            assertThat(jdbcClient.sql("SELECT count(*) FROM audit_log WHERE aggregate_id = ?")
                .param(String.valueOf(projectId)).query(Long.class).single()).isPositive());
    }

    private long createUser(String name, String role) throws Exception {
        return expect(post("/api/v1/users", null, admin(), "{\"name\":\"" + name + "\",\"role\":\"" + role + "\"}"), 201)
            .path("id").asLong();
    }

    private long accountId(String code) throws Exception {
        return expect(get("/api/v1/accounts/by-code/" + code), 200).path("id").asLong();
    }

    private String[] admin() {
        return actor(1, "ADMIN");
    }

    private String[] actor(long id, String role) {
        return new String[]{"X-Actor-Id", String.valueOf(id), "X-Actor-Role", role};
    }

    private HttpResponse<String> post(String path, String key, String[] headers, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + path))
            .header("Content-Type", "application/json")
            .headers(headers)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + path)).GET();
        if (headers.length > 0) {
            b.headers(headers);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode expect(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode())
            .as(response.request().method() + " " + response.request().uri().getPath() + " -> " + response.body())
            .isEqualTo(status);
        return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
    }
}
