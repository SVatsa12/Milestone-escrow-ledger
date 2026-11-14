package com.svatsa.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** One key, one record, ever: repeats, concurrent POSTs, tombstones and cancel races. */
@DisplayName("Provider idempotency and cancel")
class ProviderIdempotencyIT extends AbstractProviderIT {

    @Test
    @DisplayName("the same key POSTed twice: one record, the same response")
    void sameKeyTwice() throws Exception {
        // Nanoseconds on the clock: Postgres keeps microseconds, so the first answer used to carry
        // a more precise createdAt than the replay read back from the table.
        clock.set(java.time.Instant.parse("2026-03-10T06:00:00.123456789Z"));
        String key = key();
        HttpResponse<String> first = payout(key, 1_000);
        HttpResponse<String> second = payout(key, 1_000);
        assertThat(second.statusCode()).isEqualTo(first.statusCode()).isEqualTo(200);
        assertThat(body(second)).isEqualTo(body(first));
        assertThat(rows(key)).isEqualTo(1);
    }

    @Test
    @DisplayName("a repeat is never processed again, even after the mode changes")
    void repeatIgnoresTheNewMode() throws Exception {
        setMode("{\"mode\":\"FAIL\"}");
        String key = key();
        JsonNode first = expect(payout(key, 1_000), 422);
        setMode("{\"mode\":\"SUCCESS\"}");
        assertThat(expect(payout(key, 1_000), 422)).as("still the first answer").isEqualTo(first);
        assertThat(status(key)).isEqualTo("FAILED");
        assertThat(rows(key)).isEqualTo(1);
    }

    @Test
    @DisplayName("50 parallel POSTs with the same key: exactly one record, every answer the same")
    void fiftyParallelPosts() throws Exception {
        String key = key();
        List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            calls.add(http.sendAsync(payoutRequest(key, 2_500), HttpResponse.BodyHandlers.ofString()));
        }
        JsonNode expected = null;
        for (var call : calls) {
            JsonNode body = expect(call.get(), 200);
            if (expected == null) {
                expected = body;
            }
            assertThat(body.path("id")).isEqualTo(expected.path("id"));
        }
        assertThat(rows(key)).isEqualTo(1);
        assertThat(totalRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("tombstone: cancel an unknown key, then a late POST is 409 CANCELLED and nothing is paid")
    void tombstone() throws Exception {
        String key = key();
        JsonNode cancelled = expect(cancel(key), 200);
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");

        JsonNode late = expect(payout(key, 1_000), 409);
        assertThat(late.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(rows(key)).isEqualTo(1);
        assertThat(status(key)).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT amount_paise FROM provider.payouts WHERE idempotency_key = ?")
            .param(key).query(Long.class).optional().orElse(null)).as("nothing processed").isNull();
        expect(cancel(key), 200);   // cancelling again is fine
    }

    @Test
    @DisplayName("cancel: PROCESSING becomes CANCELLED and the scheduler leaves it alone; SUCCESS/FAILED are 409")
    void cancelByState() throws Exception {
        setMode("{\"mode\":\"ASYNC\"}");
        String processing = key();
        expect(payout(processing, 1_000), 202);
        assertThat(expect(cancel(processing), 200).path("status").asText()).isEqualTo("CANCELLED");
        clock.advance(java.time.Duration.ofSeconds(20));
        assertThat(payoutService.settleDueAsync()).isZero();
        assertThat(status(processing)).isEqualTo("CANCELLED");

        setMode("{\"mode\":\"SUCCESS\"}");
        String paid = key();
        expect(payout(paid, 1_000), 200);
        assertThat(expect(cancel(paid), 409).path("status").asText()).isEqualTo("SUCCESS");

        setMode("{\"mode\":\"FAIL\"}");
        String failed = key();
        expect(payout(failed, 1_000), 422);
        assertThat(expect(cancel(failed), 409).path("status").asText()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("cancel racing a POST on the same key, 100 times: never both CANCELLED and SUCCESS")
    void cancelRacingPost() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            int cancelledWins = 0;
            int postWins = 0;
            for (int i = 0; i < 100; i++) {
                String key = key();
                CyclicBarrier start = new CyclicBarrier(2);
                Future<HttpResponse<String>> post = pool.submit(() -> {
                    start.await();
                    return http.send(payoutRequest(key, 1_000), HttpResponse.BodyHandlers.ofString());
                });
                Future<HttpResponse<String>> cancel = pool.submit(() -> {
                    start.await();
                    return http.send(cancelRequest(key), HttpResponse.BodyHandlers.ofString());
                });
                HttpResponse<String> p = post.get();
                HttpResponse<String> c = cancel.get();

                assertThat(rows(key)).isEqualTo(1);
                String stored = status(key);
                if (stored.equals("CANCELLED")) {
                    // The tombstone won: the POST was rejected and never paid.
                    assertThat(p.statusCode()).as("POST after tombstone").isEqualTo(409);
                    assertThat(c.statusCode()).isEqualTo(200);
                    cancelledWins++;
                } else {
                    // The payment won: the cancel was refused with the real status.
                    assertThat(stored).isEqualTo("SUCCESS");
                    assertThat(p.statusCode()).isEqualTo(200);
                    assertThat(c.statusCode()).as("cancel after payment").isEqualTo(409);
                    assertThat(body(c).path("status").asText()).isEqualTo("SUCCESS");
                    postWins++;
                }
            }
            assertThat(cancelledWins + postWins).isEqualTo(100);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("GET by an unknown key is 404 NOT_FOUND")
    void unknownKey() throws Exception {
        assertThat(expect(byKey(key()), 404).path("code").asText()).isEqualTo("NOT_FOUND");
    }
}
