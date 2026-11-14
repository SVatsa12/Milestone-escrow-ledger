package com.svatsa.escrow.deposits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;


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
// RazorpayConfig was never registered, so it was always injected as null and every webhook was a 500.
@org.springframework.boot.context.properties.EnableConfigurationProperties(RazorpayConfig.class)
public class DepositWebhookController {
    private static final Logger log = LoggerFactory.getLogger(DepositWebhookController.class);

    private final WebhookSignatureVerifier signatureVerifier;
    private final RazorpayConfig razorpayConfig;
    private final ObjectMapper objectMapper;
    private final DepositWebhookService webhookService;

    public DepositWebhookController(
        WebhookSignatureVerifier signatureVerifier,
        RazorpayConfig razorpayConfig,
        ObjectMapper objectMapper,
        DepositWebhookService webhookService
    ) {
        this.signatureVerifier = signatureVerifier;
        this.razorpayConfig = razorpayConfig;
        this.objectMapper = objectMapper;
        this.webhookService = webhookService;
    }

    /**
     * POST /webhooks/razorpay - Receive and process Razorpay webhook.
     * 
     * Expected headers:
     * - X-Razorpay-Signature: hex(HMAC-SHA256(raw_body, webhook_secret))
     * - X-Razorpay-Event-Id: unique event ID (e.g., "evt_ABC123")
     */
    @PostMapping
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

        // Steps 3-5 run in one transaction in DepositWebhookService.
        try {
            webhookService.process(eventId, eventType, rawBody, eventData);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            // A genuine failure rolled everything back, including the dedupe row: answer 5xx so
            // Razorpay retries (this used to answer 200, and the event was lost for good).
            log.error("webhook.failed eventId={} eventType={} error={}", eventId, eventType, e.toString(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
