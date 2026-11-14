package com.svatsa.escrow.deposits;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** RazorpaySdkGateway.fetchPayments pages through GET /payments until a short page (stubbed client). */
@DisplayName("Razorpay payments paging")
class RazorpaySdkGatewayPagingTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);
    private static final Instant FROM = DAY.atStartOfDay(IST).toInstant();             // 2026-03-09T18:30:00Z
    private static final Instant TO = DAY.plusDays(1).atStartOfDay(IST).toInstant();   // 2026-03-10T18:30:00Z

    /** A fake Razorpay holding {@code total} payments, answering count/skip like the API. */
    private static final class StubPayments implements RazorpaySdkGateway.PaymentsPage {
        final int total;
        final List<JSONObject> calls = new ArrayList<>();

        StubPayments(int total) {
            this.total = total;
        }

        @Override
        public List<JSONObject> fetch(JSONObject params) {
            calls.add(new JSONObject(params.toString()));
            int skip = params.getInt("skip");
            int count = params.getInt("count");
            List<JSONObject> page = new ArrayList<>();
            for (int i = skip; i < Math.min(total, skip + count); i++) {
                page.add(new JSONObject()
                    .put("id", "pay_" + i)
                    .put("order_id", "order_" + i)
                    .put("amount", 1_000 + i)
                    .put("status", i % 10 == 0 ? "failed" : "captured")
                    .put("created_at", FROM.getEpochSecond() + i));
            }
            return page;
        }
    }

    @Test
    @DisplayName("250 payments: three pages of 100 / 100 / 50, every record read")
    void readsEveryPage() {
        StubPayments stub = new StubPayments(250);
        var payments = new RazorpaySdkGateway(null, stub).fetchPayments(FROM, TO);

        assertThat(payments).hasSize(250);
        assertThat(stub.calls).extracting(c -> c.getInt("skip")).containsExactly(0, 100, 200);
        assertThat(stub.calls).allSatisfy(c -> assertThat(c.getInt("count")).isEqualTo(100));
        assertThat(payments.get(249).paymentId()).isEqualTo("pay_249");
        assertThat(payments.get(249).amountPaise()).isEqualTo(1_249);
        assertThat(payments.get(0).status()).isEqualTo("failed");    // statuses are passed through; recon filters
        assertThat(payments.get(1).createdAt()).isEqualTo(FROM.plusSeconds(1));
    }

    @Test
    @DisplayName("exactly 200 payments: a third, empty page ends the loop")
    void stopsOnAnEmptyPage() {
        StubPayments stub = new StubPayments(200);
        assertThat(new RazorpaySdkGateway(null, stub).fetchPayments(FROM, TO)).hasSize(200);
        assertThat(stub.calls).hasSize(3);
    }

    @Test
    @DisplayName("no payments: one call")
    void emptyDay() {
        StubPayments stub = new StubPayments(0);
        assertThat(new RazorpaySdkGateway(null, stub).fetchPayments(FROM, TO)).isEmpty();
        assertThat(stub.calls).hasSize(1);
    }

    @Test
    @DisplayName("an Asia/Kolkata day becomes unix seconds [18:30Z the day before, 18:29:59Z], to inclusive")
    void istDayIsSentAsUnixSeconds() {
        StubPayments stub = new StubPayments(0);
        new RazorpaySdkGateway(null, stub).fetchPayments(FROM, TO);
        JSONObject params = stub.calls.get(0);
        assertThat(params.getLong("from")).isEqualTo(Instant.parse("2026-03-09T18:30:00Z").getEpochSecond());
        assertThat(params.getLong("to")).isEqualTo(Instant.parse("2026-03-10T18:29:59Z").getEpochSecond());
    }
}
