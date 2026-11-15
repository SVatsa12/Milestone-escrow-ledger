package com.svatsa.escrow.deposits;

import com.razorpay.Entity;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
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

    /** Razorpay's maximum page size for GET /payments. */
    static final int PAGE_SIZE = 100;

    /**
     * One page of GET /payments ({@code from}, {@code to}, {@code count}, {@code skip}). The
     * seam the paging unit test stubs; in production it is the SDK call.
     */
    @FunctionalInterface
    interface PaymentsPage {
        List<JSONObject> fetch(JSONObject params) throws RazorpayException;
    }

    private final RazorpayClient client;
    private final PaymentsPage paymentsPage;

    @Autowired
    public RazorpaySdkGateway(RazorpayConfig config) throws RazorpayException {
        // All three settings, or refuse to start. application-prod.yml has no defaults and Spring
        // resolves placeholders leniently here, so an unset key arrives as "${RAZORPAY_KEY_ID}".
        this(newClient(config));
        log.info("razorpay.gateway=RAZORPAY_SDK mode=test keyId={}", maskKey(config.keyId()));
    }

    private static RazorpayClient newClient(RazorpayConfig config) throws RazorpayException {
        config.requireCompleteForRealGateway();
        return new RazorpayClient(config.keyId(), config.keySecret());
    }

    /** "rzp_test_ABCDEFGH1234" -> "rzp_test_****1234": enough to tell keys apart in logs. */
    private static String maskKey(String keyId) {
        int keep = Math.min(4, keyId.length());
        int prefix = keyId.startsWith("rzp_test_") ? 9 : keyId.startsWith("rzp_live_") ? 9 : 0;
        return keyId.substring(0, Math.min(prefix, keyId.length() - keep)) + "****" + keyId.substring(keyId.length() - keep);
    }

    private RazorpaySdkGateway(RazorpayClient client) {
        this(client, params -> client.Payments.fetchAll(params).stream().map(Entity::toJson).toList());
    }

    RazorpaySdkGateway(RazorpayClient client, PaymentsPage paymentsPage) {
        this.client = client;
        this.paymentsPage = paymentsPage;
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
     * Every payment created in [from, to): Razorpay filters by created_at in unix SECONDS and returns
     * at most {@value #PAGE_SIZE} per call, so this pages with count/skip until a short page.
     *
     * This used to make one call, read at most one page (10 by default), and parse the SDK's
     * {@code List<Payment>} as if it were a JSON object, which threw on the first real response.
     * Razorpay's {@code to} is inclusive, so the last second is asked for as {@code to - 1}.
     */
    @Override
    public List<PaymentRecord> fetchPayments(Instant from, Instant to) {
        long fromSec = from.getEpochSecond();
        long toSec = to.getEpochSecond() - 1;
        List<PaymentRecord> records = new ArrayList<>();
        try {
            for (int skip = 0; ; skip += PAGE_SIZE) {
                JSONObject params = new JSONObject()
                    .put("from", fromSec)
                    .put("to", toSec)
                    .put("count", PAGE_SIZE)
                    .put("skip", skip);
                List<JSONObject> page = paymentsPage.fetch(params);
                for (JSONObject p : page) {
                    records.add(new PaymentRecord(
                        p.getString("id"),
                        p.optString("order_id", null),
                        p.getLong("amount"),
                        p.getString("status"),
                        Instant.ofEpochSecond(p.getLong("created_at"))));
                }
                if (page.size() < PAGE_SIZE) {
                    break;
                }
            }
            log.info("razorpay.payments-fetched from={} to={} count={}", fromSec, toSec, records.size());
            return records;
        } catch (RazorpayException e) {
            log.error("razorpay.payments-fetch-failed from={} to={} error={}", fromSec, toSec, e.getMessage());
            throw new IllegalStateException("Failed to fetch Razorpay payments", e);
        }
    }
}
