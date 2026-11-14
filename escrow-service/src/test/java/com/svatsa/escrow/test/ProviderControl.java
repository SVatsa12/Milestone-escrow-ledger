package com.svatsa.escrow.test;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.svatsa.provider.PayoutProviderApplication;
import com.svatsa.provider.payouts.PayoutRequestObserver;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The real mock-payout-provider, running in this JVM in its own Spring context, and the tests'
 * only handle on it. Everything goes through the provider's real HTTP API (directly, not
 * through Toxiproxy), except {@link #onPayoutRequest}, which uses the provider's test-only
 * {@link PayoutRequestObserver} extension point.
 *
 * <h2>Two Spring Boot apps in one JVM</h2>
 * Both modules are on one test classpath, which clashes in three places:
 * <ul>
 *   <li><b>Flyway:</b> both used to keep migrations in {@code classpath:db/migration}, so each app
 *       would see the other's V0/V1 and refuse to start. The provider's now live in
 *       {@code db/provider}.</li>
 *   <li><b>application.yml:</b> {@code classpath:application.yml} resolves to escrow-service's file
 *       (first on the classpath), so the provider would boot with escrow's settings (port 8080,
 *       Kafka, Redis, public schema). The provider context therefore loads no config file at all
 *       ({@code spring.config.name} names a file that doesn't exist) and gets every setting it
 *       needs from {@link #start} explicitly.</li>
 *   <li><b>Auto-configuration:</b> escrow's Kafka and Redis starters are on the classpath, so the
 *       provider would grow Kafka and Redis beans (and a Redis health check that is always DOWN).
 *       They are excluded.</li>
 * </ul>
 * Component scanning doesn't clash: the apps live in {@code com.svatsa.escrow} and
 * {@code com.svatsa.provider}.
 */
public final class ProviderControl {

    public enum Mode { SUCCESS, FAIL, ERROR_500, TIMEOUT_THEN_SUCCEED, TIMEOUT_THEN_FAIL, TIMEOUT_NOT_RECEIVED, ASYNC, RANDOM }

    /** A provider.payouts record as the API returns it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Record(String idempotencyKey, String status, Long amountPaise, String beneficiary, String failureReason) {}

    private final ConfigurableApplicationContext context;
    private final int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    // Fed by the observer the provider calls on every POST /v1/payouts.
    private final Map<String, AtomicInteger> postsByKey = new ConcurrentHashMap<>();
    private volatile Consumer<String> onPost = key -> { };

    private ProviderControl(ConfigurableApplicationContext context) {
        this.context = context;
        this.port = ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    /**
     * Starts the provider on a random port against this Postgres database, schema "provider", on
     * {@code clock}: the same clock escrow-service's test contexts use, so a test that moves time
     * moves it for the bank too (its settled_at and ASYNC scheduler), e.g. to reconcile a chosen day.
     */
    public static ProviderControl start(String jdbcUrl, String username, String password, java.time.Clock clock) {
        ProviderControl[] self = new ProviderControl[1];
        PayoutRequestObserver observer = key -> self[0].observed(key);
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(PayoutProviderApplication.class)
            .web(WebApplicationType.SERVLET)
            .initializers(c -> {
                c.getBeanFactory().registerSingleton("testPayoutRequestObserver", observer);
                c.getBeanFactory().registerSingleton("sharedTestClock", clock);
            })
            .properties(Map.ofEntries(
                Map.entry("spring.config.name", "mock-payout-provider-in-escrow-tests"),   // no such file
                Map.entry("spring.application.name", "mock-payout-provider"),
                Map.entry("spring.main.banner-mode", "off"),
                // Starting a second app re-initializes logging for the whole JVM; without this every
                // escrow-service log line after that point was labelled [mock-payout-provider].
                Map.entry("logging.include-application-name", "false"),
                Map.entry("server.port", "0"),
                Map.entry("spring.datasource.url", jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=provider"),
                Map.entry("spring.datasource.username", username),
                Map.entry("spring.datasource.password", password),
                Map.entry("spring.datasource.hikari.maximum-pool-size", "5"),
                Map.entry("spring.flyway.enabled", "true"),
                Map.entry("spring.flyway.schemas", "provider"),
                Map.entry("spring.flyway.locations", "classpath:db/provider"),
                Map.entry("provider.async-poll-ms", "500"),
                Map.entry("spring.autoconfigure.exclude", String.join(",",
                    "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
                    "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                    "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
                    "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"))))
            .run();
        self[0] = new ProviderControl(ctx);
        return self[0];
    }

    public int port() {
        return port;
    }

    // --- admin API -------------------------------------------------------------------------------

    public void setMode(Mode mode) {
        setMode(mode, null, null, 1L);
    }

    /** POST /admin/mode. Null delayMs / weights / seed take the provider's defaults. */
    public void setMode(Mode mode, Long delayMs, Map<Mode, Integer> weights, Long seed) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("mode", mode.name());
        if (delayMs != null) body.put("delayMs", delayMs);
        if (weights != null) body.put("weights", weights);
        if (seed != null) body.put("seed", seed);
        expect(send("POST", "/admin/mode", null, write(body)), 200);
    }

    /** POST /admin/reset (truncates provider.payouts) and forgets observed POSTs and hooks. */
    public void reset() {
        expect(send("POST", "/admin/reset", null, ""), 200);
        postsByKey.clear();
        onPost = key -> { };
    }

    // --- bank API --------------------------------------------------------------------------------

    /** GET /v1/payouts/by-key/{key}: empty on 404. */
    public Optional<Record> byKey(String key) {
        HttpResponse<String> r = send("GET", "/v1/payouts/by-key/" + key, null, null);
        if (r.statusCode() == 404) {
            return Optional.empty();
        }
        return Optional.of(read(expect(r, 200).body(), Record.class));
    }

    /** GET /v1/settlements?date= : SUCCESS payouts settled that Asia/Kolkata day. */
    public List<Record> settlements(LocalDate date) {
        Record[] records = read(expect(send("GET", "/v1/settlements?date=" + date, null, null), 200).body(), Record[].class);
        return new ArrayList<>(List.of(records));
    }

    /** POST /v1/payouts directly (not through escrow-service or Toxiproxy). */
    public HttpResponse<String> postPayout(String key, String beneficiary, long amountPaise) {
        return send("POST", "/v1/payouts", key, write(Map.of("beneficiary", beneficiary, "amountPaise", amountPaise)));
    }

    // --- observer (test-only extension point) ----------------------------------------------------

    /** How many POST /v1/payouts requests reached the provider for this key since reset(). */
    public int postCount(String key) {
        AtomicInteger n = postsByKey.get(key);
        return n == null ? 0 : n.get();
    }

    /** Runs on the provider's request thread when a POST arrives, before it is processed. */
    public void onPayoutRequest(Consumer<String> hook) {
        this.onPost = hook;
    }

    private void observed(String key) {
        postsByKey.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        onPost.accept(key);
    }

    // --- plumbing --------------------------------------------------------------------------------

    private HttpResponse<String> send(String method, String path, String idempotencyKey, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            b.header("X-Payout-Idempotency-Key", idempotencyKey);
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("provider call failed: " + method + " " + path, e);
        }
    }

    private static HttpResponse<String> expect(HttpResponse<String> r, int status) {
        if (r.statusCode() != status) {
            throw new IllegalStateException(r.request().method() + " " + r.request().uri() + " -> "
                + r.statusCode() + " " + r.body());
        }
        return r;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String body, Class<T> type) {
        try {
            return json.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException("unreadable provider response: " + body, e);
        }
    }

    ConfigurableApplicationContext context() {
        return context;
    }
}
