package com.svatsa.escrow.deposits;

import java.util.List;

/**
 * Gateway interface for Razorpay operations.
 * Allows switching between real SDK and fake implementations for testing.
 */
public interface RazorpayGateway {

    /**
     * Create an order in Razorpay.
     * 
     * @param amountPaise amount in paise (1 rupee = 100 paise)
     * @param receipt unique receipt ID (e.g., "dep_{depositId}")
     * @param notes additional metadata as key-value pairs
     * @return the Razorpay order ID
     */
    String createOrder(long amountPaise, String receipt, java.util.Map<String, String> notes);

    /**
     * Every payment created in [from, to), whatever its status, across all pages.
     * Used for reconciliation (it filters for "captured" itself).
     */
    List<PaymentRecord> fetchPayments(java.time.Instant from, java.time.Instant to);

    /**
     * A payment record from Razorpay.
     */
    record PaymentRecord(
        String paymentId,
        String orderId,
        long amountPaise,
        String status, // e.g., "captured", "failed"
        java.time.Instant createdAt
    ) {}
}
