package com.svatsa.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Provider settlements and RANDOM mode")
class ProviderSettlementsAndRandomIT extends AbstractProviderIT {

    @Test
    @DisplayName("settlements: only SUCCESS rows settled that Asia/Kolkata day (IST = UTC+5:30)")
    void settlementsRespectIstDays() throws Exception {
        // 2026-03-10 in IST runs from 2026-03-09T18:30:00Z to 2026-03-10T18:30:00Z.
        String lateOn9th = paidAt("2026-03-09T18:29:59Z");   // 23:59:59 IST on the 9th
        String firstOn10th = paidAt("2026-03-09T18:30:00Z"); // 00:00:00 IST on the 10th
        String lastOn10th = paidAt("2026-03-10T18:29:59Z");  // 23:59:59 IST on the 10th
        String firstOn11th = paidAt("2026-03-10T18:30:00Z"); // 00:00:00 IST on the 11th

        clock.set(Instant.parse("2026-03-10T06:00:00Z"));
        setMode("{\"mode\":\"FAIL\"}");
        expect(payout(key(), 500), 422);                     // FAILED: never a settlement
        setMode("{\"mode\":\"ASYNC\"}");
        expect(payout(key(), 500), 202);                     // PROCESSING: not yet
        expect(cancel(key()), 200);                          // CANCELLED tombstone: never

        assertThat(keys(settlements("2026-03-10"))).containsExactly(firstOn10th, lastOn10th);
        assertThat(keys(settlements("2026-03-09"))).containsExactly(lateOn9th);
        assertThat(keys(settlements("2026-03-11"))).containsExactly(firstOn11th);
        assertThat(keys(settlements("2026-03-12"))).isEmpty();
    }

    @Test
    @DisplayName("settlements: an ASYNC payout counts on the day it settles, not the day it was requested")
    void asyncSettlesOnItsOwnDay() throws Exception {
        clock.set(Instant.parse("2026-03-10T18:29:50Z"));    // 23:59:50 IST on the 10th
        setMode("{\"mode\":\"ASYNC\",\"seed\":3}");
        String key = key();
        expect(payout(key, 700), 202);
        clock.advance(Duration.ofSeconds(16));               // settles after midnight IST
        payoutService.settleDueAsync();
        if (status(key).equals("SUCCESS")) {
            assertThat(keys(settlements("2026-03-11"))).containsExactly(key);
        }
        assertThat(keys(settlements("2026-03-10"))).isEmpty();
    }

    @Test
    @DisplayName("RANDOM with a fixed seed is reproducible")
    void randomIsReproducible() throws Exception {
        String mode = "{\"mode\":\"RANDOM\",\"delayMs\":5,\"seed\":424242,"
            + "\"weights\":{\"SUCCESS\":3,\"FAIL\":2,\"ERROR_500\":2,\"TIMEOUT_THEN_SUCCEED\":1,"
            + "\"TIMEOUT_THEN_FAIL\":1,\"TIMEOUT_NOT_RECEIVED\":1,\"ASYNC\":2}}";

        List<String> first = run(mode, 60);
        expect(post("/admin/reset", null, ""), 200);
        List<String> second = run(mode, 60);

        assertThat(second).isEqualTo(first);
        // ...and it really mixes modes (the seed isn't stuck on one).
        assertThat(first.stream().distinct().count()).isGreaterThanOrEqualTo(5);

        expect(post("/admin/reset", null, ""), 200);
        assertThat(run(mode.replace("424242", "1"), 60)).as("another seed, another sequence").isNotEqualTo(first);
    }

    /** Sets the mode, sends n sequential POSTs, returns each one's status code and stored status. */
    private List<String> run(String mode, int n) throws Exception {
        setMode(mode);
        List<String> outcome = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String key = "rnd-" + i;
            HttpResponse<String> r = payout(key, 100);
            outcome.add(r.statusCode() + ":" + (rows(key) == 0 ? "-" : status(key)));
        }
        // ASYNC outcomes come from the same seeded RNG.
        clock.advance(Duration.ofSeconds(16));
        payoutService.settleDueAsync();
        for (int i = 0; i < n; i++) {
            String key = "rnd-" + i;
            outcome.set(i, outcome.get(i) + ">" + (rows(key) == 0 ? "-" : status(key)));
        }
        return outcome;
    }

    private String paidAt(String instant) throws Exception {
        clock.set(Instant.parse(instant));
        String key = key();
        expect(payout(key, 1_000), 200);
        return key;
    }

    private JsonNode settlements(String date) throws Exception {
        return expect(get("/v1/settlements?date=" + date), 200);
    }

    private static List<String> keys(JsonNode payouts) {
        List<String> keys = new ArrayList<>();
        payouts.forEach(p -> keys.add(p.path("idempotencyKey").asText()));
        return keys;
    }
}
