package com.svatsa.escrow.deposits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.PostingRequest;
import com.svatsa.escrow.outbox.OutboxWriter;
import com.svatsa.escrow.outbox.events.Events;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Optional;

/**
 * Webhook controller for Razorpay payment events.
 * 
 * Safety model:
 * 1. Read RAW body as byte[] (never re-serialise JSON for signature verification)
 * 2. Verify X-Razorpay-Signature with constant-time comparison
 * 3. Deduplicate by x-razorpay-event-id: INSERT ... ON CONFLICT DO NOTHING
 * 4. Process payment.captured and order.paid events atomically
 * 5. Credit wallet only from verified webhook, never from browser callback
 * 
 * Excluded from:
 * - Idempotency-Key requirement (webhooks are self-idempotent)
 * - Rate limiting (handled by webhook_events deduplication)
 */
@Controller
@RequestMapping("/webhooks/razorpay")
public class DepositWebhookController {
    private static final Logger log = LoggerFactory.getLogger(DepositWebhookController.class);

    private final WebhookSignatureVerifier signatureVerifier;
    private final RazorpayConfig razorpayConfig;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final DepositService depositService;
    private final LedgerService ledgerService;
    private final OutboxWriter outboxWriter;

    public DepositWebhookController(
        WebhookSignatureVerifier signatureVerifier,
        @org.springframework.lang.Nullable RazorpayConfig razorpayConfig,
        JdbcClient jdbcClient,
        ObjectMapper objectMapper,
        DepositService depositService,
        LedgerService ledgerService,
        OutboxWriter outboxWriter
    ) {
        this.signatureVerifier = signatureVerifier;
        this.razorpayConfig = razorpayConfig;
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.depositService = depositService;
        this.ledgerService = ledgerService;
        this.outboxWriter = outboxWriter;
    }

    /**
     * POST /webhooks/razorpay - Receive and process Razorpay webhook.
     * 
     * Expected headers:
     * - X-Razorpay-Signature: hex(HMAC-SHA256(raw_body, webhook_secret))
     * - X-Razorpay-Event-Id: unique event ID (e.g., "evt_ABC123")
     */
    @PostMapping
    @Transactional
    public ResponseEntity<?> handleWebhook(
        @RequestBody byte[] rawBody,
        @RequestHeader("X-Razorpay-Signature") String signature,
        @RequestHeader("X-Razorpay-Event-Id") String eventId
    ) {
        // Step 1: Verify signature against raw bytes
        if (!signatureVerifier.verify(rawBody, signature, razorpayConfig.webhookSecret())) {
            log.warn("Invalid webhook signature: eventId={}", eventId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // Step 2: Parse the webhook payload
        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            log.error("Failed to parse webhook payload: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        String eventType = payload.get("event").asText();
        JsonNode eventData = payload.get("contains");

        // Step 3: Deduplicate by event ID
        // INSERT ... ON CONFLICT DO NOTHING ensures this webhook is processed at most once
        Integer inserted = jdbcClient.sql(
            "INSERT INTO webhook_events (event_id, source, event_type, signature_valid, payload) " +
            "VALUES (?, ?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING"
        )
            .param(eventId)
            .param("razorpay")
            .param(eventType)
            .param(true)
            .param(rawBody.toString())
            .update();

        if (inserted == 0) {
            // Duplicate webhook: already processed
            log.debug("Duplicate webhook: eventId={}, eventType={}", eventId, eventType);
            return ResponseEntity.ok().build();
        }

        // Step 4: Handle the event based on type
        try {
            switch (eventType) {
                case "payment.captured" -> handlePaymentCaptured(eventData, eventId);
                case "order.paid" -> handleOrderPaid(eventData, eventId);
                case "payment.failed" -> handlePaymentFailed(eventData, eventId);
                default -> log.debug("Ignoring webhook event: eventType={}, eventId={}", eventType, eventId);
            }

            // Step 5: Mark the webhook as processed
            jdbcClient.sql(
                "UPDATE webhook_events SET processed_at = now() WHERE event_id = ?"
            )
                .param(eventId)
                .update();

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to process webhook: eventId={}, eventType={}, error={}",
                eventId, eventType, e.getMessage());
            // Don't rethrow: return 200 so Razorpay doesn't retry
            // The webhook_events row remains unprocessed for manual review
            return ResponseEntity.ok().build();
        }
    }

    /**
     * Handle payment.captured event.
     * Extract payment ID, order ID, and amount. Credit the wallet if amount matches.
     */
    private void handlePaymentCaptured(JsonNode eventData, String eventId) {
        String paymentId = eventData.get("payment").get("entity").get("id").asText();
        String orderId = eventData.get("payment").get("entity").get("order_id").asText();
        long capturedAmount = eventData.get("payment").get("entity").get("amount").asLong();

        log.info("Processing payment.captured: eventId={}, paymentId={}, orderId={}, amountPaise={}",
            eventId, paymentId, orderId, capturedAmount);

        processCapture(orderId, paymentId, capturedAmount, eventId);
    }

    /**
     * Handle order.paid event.
     * Similar to payment.captured but triggered on the order.
     */
    private void handleOrderPaid(JsonNode eventData, String eventId) {
        String orderId = eventData.get("order").get("entity").get("id").asText();
        String paymentId = eventData.get("order").get("entity").get("receipt").asText();
        long paidAmount = eventData.get("order").get("entity").get("amount").asLong();

        log.info("Processing order.paid: eventId={}, orderId={}, amountPaise={}",
            eventId, orderId, paidAmount);

        processCapture(orderId, paymentId, paidAmount, eventId);
    }

    /**
     * Handle payment.failed event.
     */
    private void handlePaymentFailed(JsonNode eventData, String eventId) {
        String orderId = eventData.get("payment").get("entity").get("order_id").asText();
        String reason = eventData.get("payment").get("entity").get("failure_reason").asText();

        log.info("Processing payment.failed: eventId={}, orderId={}, reason={}",
            eventId, orderId, reason);

        depositService.recordFailure(orderId, "FAILED");
    }

    /**
     * Process a capture: verify amount, credit wallet, emit event.
     * All in one transaction.
     */
    private void processCapture(String orderId, String paymentId, long capturedAmount, String eventId) {
        // Find the deposit by order ID
        Optional<DepositService.DepositRecord> depositOpt = jdbcClient.sql(
            "SELECT id, client_id, amount_paise, razorpay_order_id, razorpay_payment_id, status " +
            "FROM deposits WHERE razorpay_order_id = ? FOR UPDATE"
        )
            .param(orderId)
            .query((rs, rowNum) -> new DepositService.DepositRecord(
                rs.getLong("id"),
                rs.getLong("client_id"),
                rs.getLong("amount_paise"),
                rs.getString("razorpay_order_id"),
                rs.getString("razorpay_payment_id"),
                rs.getString("status")
            ))
            .optional();

        if (depositOpt.isEmpty()) {
            log.error("Deposit not found: orderId={}, eventId={}", orderId, eventId);
            return;
        }

        DepositService.DepositRecord deposit = depositOpt.get();

        // Verify amount matches
        if (capturedAmount != deposit.amountPaise()) {
            log.error("Amount mismatch: deposit={}, captured={}, orderId={}, eventId={}",
                deposit.amountPaise(), capturedAmount, orderId, eventId);
            depositService.recordFailure(orderId, "AMOUNT_MISMATCH");
            return;
        }

        // Check if already captured (idempotent)
        if ("CAPTURED".equals(deposit.status())) {
            log.debug("Deposit already captured: depositId={}, eventId={}", deposit.id(), eventId);
            return;
        }

        // Post to ledger: EXTERNAL_BANK:RAZORPAY -> CLIENT_WALLET
        String clientWalletCode = "CLIENT_WALLET:" + deposit.clientId();
        List<Leg> legs = List.of(
            new Leg(getAccountId("EXTERNAL_BANK:RAZORPAY"), -capturedAmount),
            new Leg(getAccountId(clientWalletCode), capturedAmount)
        );

        long entryId = ledgerService.post(
            new PostingRequest(
                "DEPOSIT",
                "DEPOSIT",
                deposit.id().toString(),
                "deposit:rzp:" + paymentId,
                "Razorpay deposit captured",
                legs
            )
        ).entryId();

        // Update deposit with payment ID and mark captured
        depositService.recordCapture(orderId, paymentId, capturedAmount);

        // Emit DepositCaptured event
        outboxWriter.write(
            "Deposit",
            deposit.id().toString(),
            "DepositCaptured",
            "escrow.payment-events",
            new Events.DepositCaptured(
                deposit.id(),
                deposit.clientId(),
                capturedAmount,
                paymentId,
                orderId,
                entryId
            )
        );

        log.info("Deposit captured: depositId={}, clientId={}, amountPaise={}, entryId={}",
            deposit.id(), deposit.clientId(), capturedAmount, entryId);
    }

    /**
     * Get account ID by code (helper).
     */
    private Long getAccountId(String code) {
        return jdbcClient.sql("SELECT id FROM accounts WHERE code = ?")
            .param(code)
            .query(Long.class)
            .single();
    }
}
