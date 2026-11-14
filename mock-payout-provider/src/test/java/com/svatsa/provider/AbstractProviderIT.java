package com.svatsa.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.svatsa.provider.payouts.PayoutService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The provider against real Postgres (Testcontainers), on its own schema exactly as in
 * production (currentSchema=provider, Flyway schemas=provider). Tests drive the ASYNC scheduler
 * themselves and move a {@link MutableClock}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "provider.async-poll-ms=3600000")
@Import(AbstractProviderIT.TestClock.class)
public abstract class AbstractProviderIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("escrow").withUsername("escrow").withPassword("escrow");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=provider");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** A clock tests can set and move. */
    public static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-03-10T06:00:00Z"));

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }

        public void set(Instant instant) { now.set(instant); }
        public void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @LocalServerPort protected int port;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected MutableClock clock;
    @Autowired protected PayoutService payoutService;

    protected final HttpClient http = HttpClient.newHttpClient();
    protected final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void cleanSlate() throws Exception {
        clock.set(Instant.parse("2026-03-10T06:00:00Z"));
        expect(post("/admin/reset", null, ""), 200);
        setMode("{\"mode\":\"SUCCESS\",\"seed\":1}");
    }

    // --- HTTP helpers -----------------------------------------------------------------------------

    protected HttpResponse<String> payout(String key, long amountPaise) throws Exception {
        return post("/v1/payouts", key, "{\"beneficiary\":\"acct-" + key + "\",\"amountPaise\":" + amountPaise + "}");
    }

    protected HttpRequest payoutRequest(String key, long amountPaise) {
        return request("/v1/payouts", key, "{\"beneficiary\":\"acct-" + key + "\",\"amountPaise\":" + amountPaise + "}");
    }

    protected HttpRequest cancelRequest(String key) {
        return request("/v1/payouts/by-key/" + key + "/cancel", null, "");
    }

    protected HttpResponse<String> cancel(String key) throws Exception {
        return http.send(cancelRequest(key), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> byKey(String key) throws Exception {
        return get("/v1/payouts/by-key/" + key);
    }

    protected JsonNode setMode(String body) throws Exception {
        return expect(post("/admin/mode", null, body), 200);
    }

    protected HttpResponse<String> post(String path, String key, String body) throws Exception {
        return http.send(request(path, key, body), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String path, String key, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            b.header("X-Payout-Idempotency-Key", key);
        }
        return b.build();
    }

    protected JsonNode expect(HttpResponse<String> response, int status) throws Exception {
        org.assertj.core.api.Assertions.assertThat(response.statusCode())
            .as(response.request().method() + " " + response.request().uri().getPath() + " -> " + response.body())
            .isEqualTo(status);
        return body(response);
    }

    protected JsonNode body(HttpResponse<String> response) throws Exception {
        return response.body() == null || response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
    }

    // --- DB helpers -------------------------------------------------------------------------------

    protected long rows(String key) {
        return jdbc.sql("SELECT count(*) FROM provider.payouts WHERE idempotency_key = ?").param(key).query(Long.class).single();
    }

    protected long totalRows() {
        return jdbc.sql("SELECT count(*) FROM provider.payouts").query(Long.class).single();
    }

    protected String status(String key) {
        return jdbc.sql("SELECT status FROM provider.payouts WHERE idempotency_key = ?").param(key).query(String.class).single();
    }

    protected static String key() {
        return "key-" + java.util.UUID.randomUUID();
    }
}
