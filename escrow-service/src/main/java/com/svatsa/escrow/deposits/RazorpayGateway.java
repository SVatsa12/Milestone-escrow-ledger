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
     * Fetch payment records for a date range.
     * Used for reconciliation.
     * 
     * @param from start time in millis since epoch
     * @param to end time in millis since epoch
     * @return list of payments in the time range
     */
    List<PaymentRecord> fetchPayments(long from, long to);

    /**
     * A payment record from Razorpay.
     */
    record PaymentRecord(
        String paymentId,
        String orderId,
        long amountPaise,
        String status // e.g., "captured", "failed"
    ) {}
}
