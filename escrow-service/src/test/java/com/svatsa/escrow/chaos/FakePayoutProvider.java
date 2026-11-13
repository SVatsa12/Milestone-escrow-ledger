package com.svatsa.escrow.chaos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-JVM payout provider for chaos tests, speaking the same HTTP contract as
 * mock-payout-provider (see AGENTS.md / P11):
 *
 * <pre>
 * POST /v1/payouts                      X-Payout-Idempotency-Key required
 *      200 SUCCESS | 202 PROCESSING | 422 FAILED | 409 CANCELLED (tombstoned key) | 500 (nothing recorded)
 *      A repeat POST for a known key returns the existing record and never pays twice.
 * GET  /v1/payouts/by-key/{key}         200 record | 404
 * POST /v1/payouts/by-key/{key}/cancel  200 CANCELLED (tombstone) | 409 record already settled
 * </pre>
 *
 * Why in-process instead of the mock-payout-provider container: the escrow-service test
 * classpath can't build that module's image, and an in-JVM server lets the test read the
 * provider's records directly for the "ledger matches provider 1:1" assertion. Toxiproxy
 * (in Docker) reaches it via Testcontainers' host port exposure.
 *
 * The server records a payout BEFORE sleeping {@code responseDelayMs}, so a downstream
 * connection reset during that sleep produces the dangerous case: the provider paid, the
 * client never heard back.
 */
public final class FakePayoutProvider {

    public enum Mode { SUCCESS, FAIL, RANDOM }

    public record ProviderRecord(String key, String status, long amountPaise, String beneficiary) {}

    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, ProviderRecord> records = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> postsByKey = new ConcurrentHashMap<>();
    private volatile Mode mode = Mode.SUCCESS;
    private volatile long responseDelayMs = 0;
    private volatile Random random = new Random(42);
    private volatile java.util.function.Consumer<String> onPost = key -> {};

    private FakePayoutProvider(HttpServer server) {
        this.server = server;
    }

    public static FakePayoutProvider start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
            FakePayoutProvider provider = new FakePayoutProvider(server);
            server.createContext("/v1/payouts", provider::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return provider;
        } catch (IOException e) {
            throw new IllegalStateException("could not start fake payout provider", e);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void reset(Mode mode, long seed, long responseDelayMs) {
        records.clear();
        postsByKey.clear();
        this.mode = mode;
        this.random = new Random(seed);
        this.responseDelayMs = responseDelayMs;
        this.onPost = key -> {};
    }

    /** Runs inside the provider when a payout POST arrives, before anything is recorded. */
    public void onPost(java.util.function.Consumer<String> hook) {
        this.onPost = hook;
    }

    public Map<String, ProviderRecord> records() {
        return Map.copyOf(records);
    }

    /** How many POST /v1/payouts requests actually reached the provider for this key. */
    public int postCount(String key) {
        AtomicInteger count = postsByKey.get(key);
        return count == null ? 0 : count.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("POST".equals(method) && path.equals("/v1/payouts")) {
                createPayout(exchange);
            } else if ("GET".equals(method) && path.startsWith("/v1/payouts/by-key/")) {
                getByKey(exchange, path.substring("/v1/payouts/by-key/".length()));
            } else if ("POST".equals(method) && path.startsWith("/v1/payouts/by-key/") && path.endsWith("/cancel")) {
                String key = path.substring("/v1/payouts/by-key/".length(), path.length() - "/cancel".length());
                cancel(exchange, key);
            } else {
                send(exchange, 404, Map.of("error", "no route"));
            }
        }
    }

    private void createPayout(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestHeaders().getFirst("X-Payout-Idempotency-Key");
        if (key == null || key.isBlank()) {
            send(exchange, 400, Map.of("error", "X-Payout-Idempotency-Key required"));
            return;
        }
        postsByKey.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        onPost.accept(key);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(exchange.getRequestBody(), Map.class);
        long amount = ((Number) body.get("amountPaise")).longValue();
        String beneficiary = String.valueOf(body.get("beneficiary"));

        String outcome;
        synchronized (this) {
            ProviderRecord existing = records.get(key);
            if (existing != null) {
                outcome = existing.status();
            } else {
                outcome = pickOutcome();
                if (!"ERROR_500".equals(outcome)) {
                    records.put(key, new ProviderRecord(key, outcome, amount, beneficiary));
                }
            }
        }

        sleep(responseDelayMs);

        switch (outcome) {
            case "SUCCESS" -> send(exchange, 200, recordBody(key));
            case "PROCESSING" -> send(exchange, 202, recordBody(key));
            case "FAILED" -> send(exchange, 422, recordBody(key));
            case "CANCELLED" -> send(exchange, 409, recordBody(key));
            default -> send(exchange, 500, Map.of("error", "internal"));
        }
    }

    private void getByKey(HttpExchange exchange, String key) throws IOException {
        synchronized (this) {
            ProviderRecord record = records.get(key);
            if (record == null) {
                send(exchange, 404, Map.of("error", "not found"));
                return;
            }
            if ("PROCESSING".equals(record.status())) {
                // An async payout settles by the time anyone asks about it.
                record = new ProviderRecord(key, random.nextInt(100) < 70 ? "SUCCESS" : "FAILED",
                    record.amountPaise(), record.beneficiary());
                records.put(key, record);
            }
        }
        send(exchange, 200, recordBody(key));
    }

    private void cancel(HttpExchange exchange, String key) throws IOException {
        synchronized (this) {
            ProviderRecord record = records.get(key);
            if (record == null) {
                records.put(key, new ProviderRecord(key, "CANCELLED", 0, null));
            } else if (!"CANCELLED".equals(record.status())) {
                send(exchange, 409, recordBody(key));
                return;
            }
        }
        send(exchange, 200, recordBody(key));
    }

    /** RANDOM: 40% SUCCESS, 20% FAILED, 20% PROCESSING, 20% 500 with nothing recorded. */
    private String pickOutcome() {
        return switch (mode) {
            case SUCCESS -> "SUCCESS";
            case FAIL -> "FAILED";
            case RANDOM -> {
                int roll = random.nextInt(100);
                if (roll < 40) yield "SUCCESS";
                if (roll < 60) yield "FAILED";
                if (roll < 80) yield "PROCESSING";
                yield "ERROR_500";
            }
        };
    }

    private Map<String, Object> recordBody(String key) {
        ProviderRecord r = records.get(key);
        return Map.of("id", key, "status", r.status(), "amountPaise", r.amountPaise(),
            "beneficiary", r.beneficiary() == null ? "" : r.beneficiary());
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
