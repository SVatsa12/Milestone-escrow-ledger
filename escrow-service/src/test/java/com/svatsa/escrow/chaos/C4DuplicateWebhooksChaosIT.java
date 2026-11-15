package com.svatsa.escrow.chaos;

import com.svatsa.escrow.deposits.DepositService;
import com.svatsa.escrow.deposits.FakeRazorpayGateway;
import com.svatsa.escrow.escrow.UserRepository;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.LedgerKeys;
import com.svatsa.escrow.test.Actors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4: duplicate webhook storm.
 *
 * 20 real deposits (DepositService, the fake Razorpay gateway). For each payment, Razorpay
 * "delivers" payment.captured 25 times with the same event id, plus order.paid for the same payment
 * 5 times under its own event id: 600 signed POSTs to /webhooks/razorpay, shuffled (seeded) and
 * sent concurrently from 32 threads. Razorpay's view of the 20 captured payments is registered in
 * the fake gateway, so reconciliation compares both sides.
 *
 * Pass: every delivery answered 200 (no 5xx, so Razorpay would never retry forever); each wallet
 * credited exactly its deposit, once; exactly one deposit:rzp:{paymentId} entry per payment; two
 * webhook_events rows per payment (one per event id); InvariantChecker clean; reconciliation of the
 * day (both sources) at 0 mismatches.
 */
@DisplayName("C4: duplicate webhook storm")
class C4DuplicateWebhooksChaosIT extends AbstractChaosIT {

    static final String SECRET = WEBHOOK_SECRET; // the base context's secret: no extra Spring context
    private static final Logger log = LoggerFactory.getLogger(C4DuplicateWebhooksChaosIT.class);

    private static final int PAYMENTS = 20;
    private static final int CAPTURED_DELIVERIES = 25;
    private static final int ORDER_PAID_DELIVERIES = 5;

    @Autowired private DepositService depositService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private FakeRazorpayGateway razorpay;

    private final HttpClient http = HttpClient.newHttpClient();

    record Payment(long clientId, long depositId, String orderId, String paymentId, long amount) {}

    record Delivery(String eventId, byte[] body) {}

    @Test
    @DisplayName("600 shuffled concurrent deliveries credit each of 20 wallets exactly once")
    void duplicateStormCreditsOnce() throws Exception {
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        razorpay.reset();

        List<Payment> payments = new ArrayList<>();
        List<Delivery> deliveries = new ArrayList<>();
        for (int i = 0; i < PAYMENTS; i++) {
            long client = userRepository.save("c4-client-" + UUID.randomUUID().toString().substring(0, 8), "CLIENT").id();
            long amount = 10_000 + 100L * i;
            var order = depositService.initiateDeposit(Actors.client(client), client, amount);
            String paymentId = "pay_c4_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            payments.add(new Payment(client, order.depositId(), order.razorpayOrderId(), paymentId, amount));
            razorpay.addFakePayment(paymentId, order.razorpayOrderId(), amount, "captured", clock.instant());

            String capturedEvent = "evt_cap_" + UUID.randomUUID();
            byte[] captured = ("{\"event\":\"payment.captured\",\"contains\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId
                + "\",\"order_id\":\"" + order.razorpayOrderId() + "\",\"amount\":" + amount + ",\"status\":\"captured\"}}}}")
                .getBytes(StandardCharsets.UTF_8);
            for (int d = 0; d < CAPTURED_DELIVERIES; d++) {
                deliveries.add(new Delivery(capturedEvent, captured));
            }
            String paidEvent = "evt_paid_" + UUID.randomUUID();
            byte[] paid = ("{\"event\":\"order.paid\",\"contains\":{\"order\":{\"entity\":{\"id\":\"" + order.razorpayOrderId()
                + "\",\"receipt\":\"" + paymentId + "\",\"amount\":" + amount + ",\"status\":\"paid\"}}}}")
                .getBytes(StandardCharsets.UTF_8);
            for (int d = 0; d < ORDER_PAID_DELIVERIES; d++) {
                deliveries.add(new Delivery(paidEvent, paid));
            }
        }
        Collections.shuffle(deliveries, new Random(4004));

        Map<Integer, Integer> statuses = new java.util.concurrent.ConcurrentHashMap<>();
        List<String> sendErrors = Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
            List<Future<?>> calls = new ArrayList<>();
            for (Delivery d : deliveries) {
                calls.add(pool.submit(() -> {
                    try {
                        HttpResponse<String> r = http.send(request(d), HttpResponse.BodyHandlers.ofString());
                        statuses.merge(r.statusCode(), 1, Integer::sum);
                    } catch (Exception e) {
                        sendErrors.add(e.toString());
                    }
                }));
            }
            for (Future<?> f : calls) {
                f.get(2, TimeUnit.MINUTES);
            }
        }
        log.info("c4.storm deliveries={} statuses={} sendErrors={}", deliveries.size(), statuses, sendErrors.size());

        // --- Verify ---------------------------------------------------------------------------------
        List<String> failures = new ArrayList<>(sendErrors);
        if (!statuses.keySet().equals(java.util.Set.of(200))) {
            failures.add("every delivery must be answered 200, got " + statuses);
        }
        for (Payment p : payments) {
            long balance = accountRepository.findByCode("CLIENT_WALLET:" + p.clientId()).orElseThrow().balance();
            if (balance != p.amount()) {
                failures.add("client " + p.clientId() + " wallet " + balance + ", expected exactly " + p.amount());
            }
            long entries = jdbcClient.sql("SELECT count(*) FROM journal_entries WHERE idempotency_key = ?")
                .param(LedgerKeys.deposit(p.paymentId())).query(Long.class).single();
            if (entries != 1) {
                failures.add(p.paymentId() + " has " + entries + " deposit entries");
            }
            String status = jdbcClient.sql("SELECT status FROM deposits WHERE id = ?").param(p.depositId())
                .query(String.class).single();
            if (!status.equals("CAPTURED")) {
                failures.add("deposit " + p.depositId() + " is " + status);
            }
        }
        long eventRows = jdbcClient.sql("SELECT count(*) FROM webhook_events WHERE payload::text LIKE '%pay_c4_%'")
            .query(Long.class).single();
        if (eventRows != 2L * PAYMENTS) {
            failures.add("webhook_events has " + eventRows + " rows for this storm, expected " + 2 * PAYMENTS);
        }

        List<String> violations = new ArrayList<>(failures);
        violations.addAll(describe(invariantChecker.check()));
        List<String> mismatches = reconcileToday();

        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("payments", PAYMENTS);
        notes.put("deliveries", deliveries.size());
        notes.put("capturedDeliveriesPerPayment", CAPTURED_DELIVERIES);
        notes.put("orderPaidDeliveriesPerPayment", ORDER_PAID_DELIVERIES);
        notes.put("httpStatuses", statuses);
        notes.put("webhookEventRows", eventRows);
        var file = ChaosResults.write("C4-duplicate-webhooks", startedAt,
            Duration.ofNanos(System.nanoTime() - startNanos).toMillis(), deliveries.size(), violations, mismatches, notes);
        log.info("c4.result file={} violations={} mismatches={}", file, violations.size(), mismatches.size());

        assertThat(violations).as("C4 violations").isEmpty();
        assertThat(mismatches).as("C4 reconciliation mismatches").isEmpty();
    }

    private HttpRequest request(Delivery d) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + "/webhooks/razorpay"))
            .header("Content-Type", "application/json")
            .header("X-Razorpay-Signature", sign(d.body()))
            .header("X-Razorpay-Event-Id", d.eventId())
            .POST(HttpRequest.BodyPublishers.ofByteArray(d.body()))
            .build();
    }

    private static String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
