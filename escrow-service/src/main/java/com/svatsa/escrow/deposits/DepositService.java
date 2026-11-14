package com.svatsa.escrow.deposits;

import com.svatsa.escrow.common.Actor;
import com.svatsa.escrow.common.Authz;
import com.svatsa.escrow.common.Role;
import com.svatsa.escrow.common.ValidationException;
import com.svatsa.escrow.idempotency.IdempotencyService;
import com.svatsa.escrow.outbox.OutboxWriter;
import com.svatsa.escrow.outbox.events.Events;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Service for handling deposit creation and wallet top-ups via Razorpay.
 * 
 * Flow:
 * 1. Client calls POST /api/v1/deposits with amount and Idempotency-Key
 * 2. Service creates a deposits row in CREATED status
 * 3. Service calls Razorpay to create an order
 * 4. Returns deposit details to client
 * 5. Client shows Razorpay Checkout UI
 * 6. After payment, Razorpay sends webhook to POST /webhooks/razorpay
 * 7. Webhook handler credits the wallet via verified HMAC signature
 */
@Service
public class DepositService {
    private static final Logger log = LoggerFactory.getLogger(DepositService.class);

    private final JdbcClient jdbcClient;
    private final RazorpayGateway razorpayGateway;
    private final OutboxWriter outboxWriter;

    public DepositService(
        JdbcClient jdbcClient,
        RazorpayGateway razorpayGateway,
        OutboxWriter outboxWriter
    ) {
        this.jdbcClient = jdbcClient;
        this.razorpayGateway = razorpayGateway;
        this.outboxWriter = outboxWriter;
    }

    /**
     * Create a deposit order.
     * 
     * @param actor who is asking: only the client themselves may top up their wallet
     * @param clientId ID of the client whose wallet is credited
     * @param amountPaise amount in paise
     * @return deposit details including Razorpay order ID
     */
    @Transactional
    public DepositResponse initiateDeposit(Actor actor, long clientId, long amountPaise) {
        Authz.require(actor.is(Role.CLIENT, clientId), actor, "top up the wallet of client " + clientId);
        if (amountPaise <= 0) {
            throw new ValidationException("Amount must be positive");
        }

        // Create deposit row in CREATED status
        Long depositId = jdbcClient.sql(
            "INSERT INTO deposits (client_id, amount_paise, razorpay_order_id, status) " +
            "VALUES (?, ?, ?, ?) RETURNING id"
        )
            .param(clientId)
            .param(amountPaise)
            .param("temp") // Will update after creating order
            .param("CREATED")
            .query(Long.class)
            .single();

        // Create Razorpay order
        Map<String, String> notes = new HashMap<>();
        notes.put("depositId", depositId.toString());
        notes.put("clientId", Long.toString(clientId));

        String orderId = razorpayGateway.createOrder(
            amountPaise,
            "dep_" + depositId,
            notes
        );

        // Update deposits row with real order ID
        jdbcClient.sql(
            "UPDATE deposits SET razorpay_order_id = ? WHERE id = ?"
        )
            .param(orderId)
            .param(depositId)
            .update();

        log.info("Created deposit: depositId={}, clientId={}, orderId={}, amountPaise={}",
            depositId, clientId, orderId, amountPaise);

        return new DepositResponse(
            depositId,
            orderId,
            razorpayGateway.toString().contains("Fake") ? "test-key" : System.getenv("RAZORPAY_KEY_ID"),
            amountPaise
        );
    }

    /**
     * Fetch deposit status.
     */
    @Transactional(readOnly = true)
    public Optional<DepositRecord> getDeposit(Long depositId) {
        return jdbcClient.sql(
            "SELECT id, client_id, amount_paise, razorpay_order_id, razorpay_payment_id, status " +
            "FROM deposits WHERE id = ?"
        )
            .param(depositId)
            .query((rs, rowNum) -> new DepositRecord(
                rs.getLong("id"),
                rs.getLong("client_id"),
                rs.getLong("amount_paise"),
                rs.getString("razorpay_order_id"),
                rs.getString("razorpay_payment_id"),
                rs.getString("status")
            ))
            .optional();
    }

    /**
     * Mark a deposit as captured (called from webhook handler).
     * This method is internal and should only be called by webhook processing.
     */
    @Transactional
    public void recordCapture(String orderId, String paymentId, long capturedAmount) {
        jdbcClient.sql(
            "UPDATE deposits SET " +
            "  razorpay_payment_id = ?, " +
            "  status = 'CAPTURED', " +
            "  updated_at = now() " +
            "WHERE razorpay_order_id = ? AND status IN ('CREATED', 'CAPTURED')"
        )
            .param(paymentId)
            .param(orderId)
            .update();

        log.info("Recorded capture: orderId={}, paymentId={}, amountPaise={}",
            orderId, paymentId, capturedAmount);
    }

    /**
     * Mark a deposit as failed.
     */
    @Transactional
    public void recordFailure(String orderId, String reason) {
        jdbcClient.sql(
            "UPDATE deposits SET " +
            "  status = ?, " +
            "  updated_at = now() " +
            "WHERE razorpay_order_id = ?"
        )
            .param(reason)
            .param(orderId)
            .update();

        log.warn("Recorded deposit failure: orderId={}, reason={}", orderId, reason);
    }

    /**
     * DTO for deposit response.
     */
    public record DepositResponse(
        Long depositId,
        String razorpayOrderId,
        String razorpayKeyId,
        long amountPaise
    ) {}

    /**
     * DTO for deposit record.
     */
    public record DepositRecord(
        Long id,
        Long clientId,
        long amountPaise,
        String razorpayOrderId,
        String razorpayPaymentId,
        String status
    ) {}
}
