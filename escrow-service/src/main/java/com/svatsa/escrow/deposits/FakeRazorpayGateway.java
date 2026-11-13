package com.svatsa.escrow.deposits;

import org.springframework.context.annotation.Primary;
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
@Primary
public class FakeRazorpayGateway implements RazorpayGateway {

    private final Map<String, FakeOrder> orders = new ConcurrentHashMap<>();
    private final Map<String, FakePayment> payments = new ConcurrentHashMap<>();

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

    /**
     * Fetch fake payments.
     * Returns all payments created so far (in-memory only).
     */
    @Override
    public List<RazorpayGateway.PaymentRecord> fetchPayments(long from, long to) {
        return payments.values().stream()
            .map(p -> new RazorpayGateway.PaymentRecord(
                p.paymentId(),
                p.orderId(),
                p.amountPaise(),
                p.status()
            ))
            .toList();
    }

    /**
     * Add a fake payment (for tests).
     */
    public void addFakePayment(String paymentId, String orderId, long amountPaise, String status) {
        payments.put(paymentId, new FakePayment(paymentId, orderId, amountPaise, status));
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

    record FakePayment(String paymentId, String orderId, long amountPaise, String status) {}

}
