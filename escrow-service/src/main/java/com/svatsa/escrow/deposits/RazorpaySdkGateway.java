package com.svatsa.escrow.deposits;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Real Razorpay SDK gateway implementation.
 * Uses com.razorpay.RazorpayClient to interact with Razorpay API.
 * Only instantiated if razorpay.key-id and razorpay.key-secret are configured.
 */
@Component
// Real Razorpay (test mode) whenever a key id is configured. The old condition was
// havingValue = "not_empty", which only matched the literal string "not_empty": never in practice.
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("!'${razorpay.key-id:}'.isBlank()")
public class RazorpaySdkGateway implements RazorpayGateway {
    private static final Logger log = LoggerFactory.getLogger(RazorpaySdkGateway.class);

    private final RazorpayClient client;

    public RazorpaySdkGateway(RazorpayConfig config) throws RazorpayException {
        this.client = new RazorpayClient(config.keyId(), config.keySecret());
    }

    /**
     * Create an order in Razorpay.
     * 
     * Currency is always INR (Indian Rupees).
     * amount is in paise (smallest unit, 100 paise = 1 rupee).
     */
    @Override
    public String createOrder(long amountPaise, String receipt, Map<String, String> notes) {
        try {
            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount", amountPaise);
            orderRequest.put("currency", "INR");
            orderRequest.put("receipt", receipt);
            
            JSONObject notesJson = new JSONObject();
            for (Map.Entry<String, String> entry : notes.entrySet()) {
                notesJson.put(entry.getKey(), entry.getValue());
            }
            orderRequest.put("notes", notesJson);

            Object orderResponse = client.Orders.create(orderRequest);
            JSONObject order;
            if (orderResponse instanceof JSONObject) {
                order = (JSONObject) orderResponse;
            } else {
                order = new JSONObject(orderResponse.toString());
            }
            String orderId = order.getString("id");
            
            log.info("Created Razorpay order: orderId={}, receipt={}, amountPaise={}",
                orderId, receipt, amountPaise);
            
            return orderId;
        } catch (RazorpayException e) {
            log.error("Failed to create Razorpay order: {}", e.getMessage());
            throw new IllegalStateException("Failed to create Razorpay order", e);
        }
    }

    /**
     * Fetch payments for a date range.
     * Uses the Payments API with filters.
     */
    @Override
    public List<PaymentRecord> fetchPayments(long from, long to) {
        try {
            JSONObject params = new JSONObject();
            params.put("from", from / 1000); // Convert millis to seconds
            params.put("to", to / 1000);

            Object response = client.Payments.fetchAll(params);
            JSONObject responseJson;
            if (response instanceof JSONObject) {
                responseJson = (JSONObject) response;
            } else {
                responseJson = new JSONObject(response.toString());
            }
            
            JSONArray payments = responseJson.getJSONArray("items");
            List<PaymentRecord> records = new ArrayList<>();
            
            for (int i = 0; i < payments.length(); i++) {
                JSONObject payment = payments.getJSONObject(i);
                records.add(new PaymentRecord(
                    payment.getString("id"),
                    payment.optString("order_id", null),
                    payment.getLong("amount"),
                    payment.getString("status")
                ));
            }
            
            log.debug("Fetched {} payments from Razorpay", records.size());
            return records;
        } catch (RazorpayException e) {
            log.error("Failed to fetch Razorpay payments: {}", e.getMessage());
            throw new IllegalStateException("Failed to fetch Razorpay payments", e);
        }
    }
}
