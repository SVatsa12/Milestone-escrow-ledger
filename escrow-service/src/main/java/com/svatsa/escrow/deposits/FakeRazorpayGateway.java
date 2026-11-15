package com.svatsa.escrow.deposits;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fake Razorpay gateway for tests and local development.
 * Instantiated when razorpay.key-id is NOT configured.
 * Generates fake order and payment IDs and tracks them in memory.
 */
@Component
// Only when no Razorpay key is configured (local dev, tests). It used to be an unconditional
// @Primary, so the app always used the fake gateway, even with real keys set.
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${razorpay.key-id:}'.isBlank()")
public class FakeRazorpayGateway implements RazorpayGateway {

    private static final Logger log = LoggerFactory.getLogger(FakeRazorpayGateway.class);

    private final Map<String, FakeOrder> orders = new ConcurrentHashMap<>();
    private final Map<String, FakePayment> payments = new ConcurrentHashMap<>();

    public FakeRazorpayGateway(RazorpayConfig config) {
        // Selected because RAZORPAY_KEY_ID is blank. A key secret without it is a half-configured
        // test mode: fail rather than quietly take money-shaped requests with a fake.
        if (config.wantsRealGateway()) {
            config.requireCompleteForRealGateway();
        }
        log.warn("razorpay.gateway=FAKE: RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET are not set, so orders and payments "
            + "are simulated in memory and nothing reaches Razorpay (webhook secret {})",
            RazorpayConfig.missing(config.webhookSecret()) ? "not set: webhooks are rejected" : "set");
    }

    /**
     * Create a fake order.
     * Returns order ID in format "order_{randomUUID}".
     */
    @Override
    public String createOrder(long amountPaise, String receipt, Map<String, String> notes) {
        String orderId = "order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        orders.put(orderId, new FakeOrder(orderId, receipt, amountPaise, notes));
        return orderId;
    }

    /** Fake payments created in [from, to), like the real API's from/to filter. */
    @Override
    public List<RazorpayGateway.PaymentRecord> fetchPayments(java.time.Instant from, java.time.Instant to) {
        return payments.values().stream()
            .filter(p -> !p.createdAt().isBefore(from) && p.createdAt().isBefore(to))
            .map(p -> new RazorpayGateway.PaymentRecord(p.paymentId(), p.orderId(), p.amountPaise(), p.status(), p.createdAt()))
            .toList();
    }

    /**
     * Add a fake payment (for tests): what Razorpay would report for it.
     */
    public void addFakePayment(String paymentId, String orderId, long amountPaise, String status,
                               java.time.Instant createdAt) {
        payments.put(paymentId, new FakePayment(paymentId, orderId, amountPaise, status, createdAt));
    }

    /**
     * Get a fake order (for tests).
     */
    public FakeOrder getOrder(String orderId) {
        return orders.get(orderId);
    }

    /**
     * Clear all fake data (for test isolation).
     */
    public void reset() {
        orders.clear();
        payments.clear();
    }

    record FakeOrder(String orderId, String receipt, long amountPaise, Map<String, String> notes) {}

    record FakePayment(String paymentId, String orderId, long amountPaise, String status, java.time.Instant createdAt) {}

}
