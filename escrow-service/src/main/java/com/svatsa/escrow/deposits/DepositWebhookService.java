package com.svatsa.escrow.deposits;

import com.fasterxml.jackson.databind.JsonNode;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.PostingRequest;
import com.svatsa.escrow.outbox.OutboxWriter;
import com.svatsa.escrow.outbox.events.Events;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Processes a verified Razorpay webhook in ONE transaction: the webhook_events dedupe row, the
 * deposit update, the ledger posting and the outbox event commit or roll back together.
 *
 * This used to run in the controller with no transaction at all: LedgerService.post (MANDATORY)
 * threw, the controller swallowed it and answered 200, and the dedupe row was already committed,
 * so Razorpay's retries were ignored as duplicates. No captured payment ever credited a wallet.
 */
@Service
public class DepositWebhookService {
    private static final Logger log = LoggerFactory.getLogger(DepositWebhookService.class);

    private final JdbcClient jdbcClient;
    private final DepositService depositService;
    private final LedgerService ledgerService;
    private final OutboxWriter outboxWriter;

    public DepositWebhookService(JdbcClient jdbcClient, DepositService depositService,
                                 LedgerService ledgerService, OutboxWriter outboxWriter) {
        this.jdbcClient = jdbcClient;
        this.depositService = depositService;
        this.ledgerService = ledgerService;
        this.outboxWriter = outboxWriter;
    }

    /** Returns false for a duplicate event id (already processed), true otherwise. */
    @Transactional
    public boolean process(String eventId, String eventType, byte[] rawBody, JsonNode eventData) {
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
            .param(new String(rawBody, java.nio.charset.StandardCharsets.UTF_8)) // was rawBody.toString(): "[B@1a2b..." into a jsonb column
            .update();

        if (inserted == 0) {
            log.debug("webhook.duplicate eventId={} eventType={}", eventId, eventType);
            return false;
        }

        switch (eventType) {
            case "payment.captured" -> handlePaymentCaptured(eventData, eventId);
            case "order.paid" -> handleOrderPaid(eventData, eventId);
            case "payment.failed" -> handlePaymentFailed(eventData, eventId);
            default -> log.debug("webhook.ignored eventType={} eventId={}", eventType, eventId);
        }

        jdbcClient.sql("UPDATE webhook_events SET processed_at = now() WHERE event_id = ?")
            .param(eventId)
            .update();
        return true;
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
