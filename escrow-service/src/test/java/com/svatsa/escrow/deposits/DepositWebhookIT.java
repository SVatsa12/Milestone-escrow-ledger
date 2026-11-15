package com.svatsa.escrow.deposits;

import com.svatsa.escrow.test.Actors;
import com.svatsa.escrow.escrow.User;
import com.svatsa.escrow.escrow.UserRepository;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P9: Razorpay webhooks, signed with the test secret, against real deposits created through
 * DepositService (the fake gateway is active because no razorpay.key-id is configured).
 * Every test checks the money, not just the HTTP status: the old version only checked
 * webhook_events rows, which is how "no capture ever credited a wallet" went unnoticed.
 */
@DisplayName("Razorpay webhooks (P9)")
class DepositWebhookIT extends AbstractIntegrationTest {

    static final String SECRET = WEBHOOK_SECRET; // the base context's secret: no extra Spring context

    @Autowired private DepositService depositService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;

    private final HttpClient http = HttpClient.newHttpClient();
    private User client;

    @BeforeEach
    void setUp() {
        client = userRepository.save("depositor-" + UUID.randomUUID().toString().substring(0, 8), "CLIENT");
    }

    @Test
    @DisplayName("a valid payment.captured credits the wallet exactly once")
    void validCaptureCreditsTheWallet() throws Exception {
        var order = depositService.initiateDeposit(Actors.client(client.id()), client.id(), 50_000);

        assertThat(send(evt(), captured(order.razorpayOrderId(), pay(), 50_000)).statusCode()).isEqualTo(200);

        assertThat(wallet()).isEqualTo(50_000);
        assertThat(depositStatus(order)).isEqualTo("CAPTURED");
    }

    @Test
    @DisplayName("the same event id 50 times at once → one credit")
    void concurrentDuplicateEventsCreditOnce() throws Exception {
        var order = depositService.initiateDeposit(Actors.client(client.id()), client.id(), 20_000);
        String eventId = evt();
        byte[] body = captured(order.razorpayOrderId(), pay(), 20_000);

        List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            calls.add(http.sendAsync(request(eventId, body, sign(body)), HttpResponse.BodyHandlers.ofString()));
        }
        for (var c : calls) {
            assertThat(c.get().statusCode()).isEqualTo(200);
        }

        assertThat(wallet()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("order.paid after payment.captured (new event id, same payment) → still one credit")
    void orderPaidAfterCaptureCreditsOnce() throws Exception {
        var order = depositService.initiateDeposit(Actors.client(client.id()), client.id(), 30_000);
        String paymentId = pay();

        assertThat(send(evt(), captured(order.razorpayOrderId(), paymentId, 30_000)).statusCode()).isEqualTo(200);
        assertThat(send(evt(), orderPaid(order.razorpayOrderId(), paymentId, 30_000)).statusCode()).isEqualTo(200);

        assertThat(wallet()).as("the business key (the deposit) catches the second event").isEqualTo(30_000);
    }

    @Test
    @DisplayName("the ledger key deposit:rzp:{paymentId} holds even if the deposit row's CAPTURED guard is bypassed")
    void depositKeyHoldsWithoutTheStatusGuard() throws Exception {
        var order = depositService.initiateDeposit(Actors.client(client.id()), client.id(), 40_000);
        String paymentId = pay();
        assertThat(send(evt(), captured(order.razorpayOrderId(), paymentId, 40_000)).statusCode()).isEqualTo(200);

        // Put the deposit back to CREATED behind the service's back, then deliver the same
        // payment under a new event id (a new "request"): only the ledger key can stop it now.
        jdbcClient.sql("UPDATE deposits SET status = 'CREATED' WHERE id = ?").param(order.depositId()).update();
        assertThat(send(evt(), captured(order.razorpayOrderId(), paymentId, 40_000)).statusCode()).isEqualTo(200);

        assertThat(wallet()).as("credited once").isEqualTo(40_000);
        assertThat(jdbcClient.sql("SELECT idempotency_key FROM journal_entries WHERE type = 'DEPOSIT' AND reference_id = ?")
            .param(order.depositId().toString()).query(String.class).list())
            .containsExactly(com.svatsa.escrow.ledger.LedgerKeys.deposit(paymentId));
    }

    @Test
    @DisplayName("a bad signature → 400, nothing recorded, no money")
    void badSignatureIsRejected() throws Exception {
        var order = depositService.initiateDeposit(Actors.client(client.id()), client.id(), 10_000);
        String eventId = evt();
        byte[] body = captured(order.razorpayOrderId(), pay(), 10_000);

        HttpResponse<String> response = http.send(request(eventId, body, sign("tampered".getBytes())),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM webhook_events WHERE event_id = ?")
            .param(eventId).query(Long.class).single()).isZero();
        assertThat(wallet()).isZero();
    }

    @Test
    @DisplayName("an amount that doesn't match the order → AMOUNT_MISMATCH, no money")
    void amountMismatchMovesNoMoney() throws Exception {
        var order = depositService.initiateDeposit(Actors.client(client.id()), client.id(), 10_000);

        assertThat(send(evt(), captured(order.razorpayOrderId(), pay(), 9_999)).statusCode()).isEqualTo(200);

        assertThat(depositStatus(order)).isEqualTo("AMOUNT_MISMATCH");
        assertThat(wallet()).isZero();
    }

    @Test
    @DisplayName("the signature is checked against the raw bytes, not re-serialised JSON")
    void signatureIsOverRawBytes() throws Exception {
        var order = depositService.initiateDeposit(Actors.client(client.id()), client.id(), 15_000);
        String compact = new String(captured(order.razorpayOrderId(), pay(), 15_000), StandardCharsets.UTF_8);
        byte[] spaced = compact.replace(",", ", ").replace(":", ": ").getBytes(StandardCharsets.UTF_8);

        // Same JSON with extra whitespace, signed over exactly those bytes → valid.
        assertThat(http.send(request(evt(), spaced, sign(spaced)), HttpResponse.BodyHandlers.ofString()).statusCode())
            .isEqualTo(200);
        assertThat(wallet()).isEqualTo(15_000);

        // The compact (re-serialised) bytes with the signature of the spaced bytes → invalid.
        assertThat(http.send(request(evt(), compact.getBytes(StandardCharsets.UTF_8), sign(spaced)),
            HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    }

    // --- helpers -----------------------------------------------------------------------------

    private static byte[] captured(String orderId, String paymentId, long amount) {
        return ("{\"event\":\"payment.captured\",\"contains\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId
            + "\",\"order_id\":\"" + orderId + "\",\"amount\":" + amount + ",\"status\":\"captured\"}}}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] orderPaid(String orderId, String paymentId, long amount) {
        return ("{\"event\":\"order.paid\",\"contains\":{\"order\":{\"entity\":{\"id\":\"" + orderId
            + "\",\"receipt\":\"" + paymentId + "\",\"amount\":" + amount + ",\"status\":\"paid\"}}}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private HttpResponse<String> send(String eventId, byte[] body) throws Exception {
        return http.send(request(eventId, body, sign(body)), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String eventId, byte[] body, String signature) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + "/webhooks/razorpay"))
            .header("Content-Type", "application/json")
            .header("X-Razorpay-Signature", signature)
            .header("X-Razorpay-Event-Id", eventId)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
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

    private long wallet() {
        return accountRepository.findByCode("CLIENT_WALLET:" + client.id()).orElseThrow().balance();
    }

    private String depositStatus(DepositService.DepositResponse order) {
        return jdbcClient.sql("SELECT status FROM deposits WHERE id = ?").param(order.depositId()).query(String.class).single();
    }

    private static String evt() {
        return "evt_" + UUID.randomUUID();
    }

    private static String pay() {
        return "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }
}
