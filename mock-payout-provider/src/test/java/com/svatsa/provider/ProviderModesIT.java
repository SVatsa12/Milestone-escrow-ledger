package com.svatsa.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** One test per mode: HTTP status, what was persisted, and that the timeout modes really wait. */
@DisplayName("Provider modes")
class ProviderModesIT extends AbstractProviderIT {

    private static final long DELAY_MS = 400;

    @Test
    @DisplayName("SUCCESS: 200, SUCCESS persisted with settled_at")
    void success() throws Exception {
        String key = key();
        JsonNode body = expect(payout(key, 1_000), 200);
        assertThat(body.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(body.path("amountPaise").asLong()).isEqualTo(1_000);
        assertThat(body.path("beneficiary").asText()).isEqualTo("acct-" + key);
        assertThat(status(key)).isEqualTo("SUCCESS");
        assertThat(jdbc.sql("SELECT settled_at IS NOT NULL FROM provider.payouts WHERE idempotency_key = ?")
            .param(key).query(Boolean.class).single()).isTrue();
        // GET by key sees the same record.
        assertThat(expect(byKey(key), 200).path("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("FAIL: 422, FAILED persisted with INVALID_ACCOUNT")
    void fail() throws Exception {
        setMode("{\"mode\":\"FAIL\"}");
        String key = key();
        JsonNode body = expect(payout(key, 1_000), 422);
        assertThat(body.path("status").asText()).isEqualTo("FAILED");
        assertThat(body.path("failureReason").asText()).isEqualTo("INVALID_ACCOUNT");
        assertThat(status(key)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("ERROR_500: 500, nothing persisted")
    void error500() throws Exception {
        setMode("{\"mode\":\"ERROR_500\"}");
        String key = key();
        expect(payout(key, 1_000), 500);
        assertThat(rows(key)).isZero();
        expect(byKey(key), 404);
    }

    @Test
    @DisplayName("TIMEOUT_THEN_SUCCEED: SUCCESS is persisted before the late 200")
    void timeoutThenSucceed() throws Exception {
        setMode("{\"mode\":\"TIMEOUT_THEN_SUCCEED\",\"delayMs\":" + DELAY_MS + "}");
        String key = key();
        long start = System.nanoTime();
        CompletableFuture<HttpResponse<String>> call = http.sendAsync(payoutRequest(key, 1_000), HttpResponse.BodyHandlers.ofString());

        // The bank has paid while the caller is still waiting for an answer.
        await().atMost(Duration.ofMillis(DELAY_MS)).until(() -> rows(key) == 1);
        assertThat(call).isNotDone();
        assertThat(status(key)).isEqualTo("SUCCESS");

        expect(call.get(5, TimeUnit.SECONDS), 200);
        assertThat(elapsedMs(start)).isGreaterThanOrEqualTo(DELAY_MS);
    }

    @Test
    @DisplayName("TIMEOUT_THEN_FAIL: FAILED is persisted before the late 422")
    void timeoutThenFail() throws Exception {
        setMode("{\"mode\":\"TIMEOUT_THEN_FAIL\",\"delayMs\":" + DELAY_MS + "}");
        String key = key();
        long start = System.nanoTime();
        CompletableFuture<HttpResponse<String>> call = http.sendAsync(payoutRequest(key, 1_000), HttpResponse.BodyHandlers.ofString());

        await().atMost(Duration.ofMillis(DELAY_MS)).until(() -> rows(key) == 1);
        assertThat(call).isNotDone();
        assertThat(status(key)).isEqualTo("FAILED");

        expect(call.get(5, TimeUnit.SECONDS), 422);
        assertThat(elapsedMs(start)).isGreaterThanOrEqualTo(DELAY_MS);
    }

    @Test
    @DisplayName("TIMEOUT_NOT_RECEIVED: waits, then 504, and nothing is ever persisted")
    void timeoutNotReceived() throws Exception {
        setMode("{\"mode\":\"TIMEOUT_NOT_RECEIVED\",\"delayMs\":" + DELAY_MS + "}");
        String key = key();
        long start = System.nanoTime();
        expect(payout(key, 1_000), 504);
        assertThat(elapsedMs(start)).isGreaterThanOrEqualTo(DELAY_MS);
        assertThat(rows(key)).isZero();
        expect(byKey(key), 404);
    }

    @Test
    @DisplayName("ASYNC: 202 PROCESSING; the scheduler settles it 5–15s later (by the injected clock)")
    void async() throws Exception {
        setMode("{\"mode\":\"ASYNC\",\"seed\":7}");
        String key = key();
        assertThat(expect(payout(key, 1_000), 202).path("status").asText()).isEqualTo("PROCESSING");

        clock.advance(Duration.ofMillis(4_999));
        assertThat(payoutService.settleDueAsync()).isZero();
        assertThat(status(key)).as("not before 5s").isEqualTo("PROCESSING");
        expect(payout(key, 1_000), 202);   // a repeat POST still sees PROCESSING

        clock.advance(Duration.ofSeconds(11));   // now 15.999s after the request
        assertThat(payoutService.settleDueAsync()).isEqualTo(1);
        assertThat(status(key)).isIn("SUCCESS", "FAILED");
        JsonNode settled = expect(byKey(key), 200);
        assertThat(settled.path("settledAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("ASYNC outcomes are about 90% SUCCESS and 10% FAILED")
    void asyncOutcomeSplit() throws Exception {
        setMode("{\"mode\":\"ASYNC\",\"seed\":11}");
        int n = 200;
        for (int i = 0; i < n; i++) {
            expect(payout(key(), 100), 202);
        }
        clock.advance(Duration.ofSeconds(16));
        assertThat(payoutService.settleDueAsync()).isEqualTo(n);
        long failed = jdbc.sql("SELECT count(*) FROM provider.payouts WHERE status = 'FAILED'").query(Long.class).single();
        assertThat(failed).isBetween(5L, 40L);
    }

    @Test
    @DisplayName("mode defaults and validation: GET reports the mode in use; bad input is 400")
    void adminMode() throws Exception {
        JsonNode mode = expect(get("/admin/mode"), 200);
        assertThat(mode.path("mode").asText()).isEqualTo("SUCCESS");
        assertThat(mode.path("seed").asLong()).isEqualTo(1);

        expect(post("/admin/mode", null, "{\"mode\":\"NOPE\"}"), 400);
        expect(post("/admin/mode", null, "{\"mode\":\"RANDOM\",\"weights\":{\"RANDOM\":1}}"), 400);
        expect(post("/admin/mode", null, "{\"mode\":\"RANDOM\",\"weights\":{\"SUCCESS\":0}}"), 400);
        expect(post("/admin/mode", null, "{\"delayMs\":5}"), 400);
        assertThat(expect(get("/admin/mode"), 200).path("mode").asText()).isEqualTo("SUCCESS");
        expect(post("/v1/payouts", null, "{\"beneficiary\":\"x\",\"amountPaise\":1}"), 400);   // no key
        expect(payout(key(), 0), 400);
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
