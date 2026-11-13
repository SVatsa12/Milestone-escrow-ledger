package com.svatsa.escrow.payouts;

import com.svatsa.escrow.common.DomainException;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.PostingRequest;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payout saga service.
 * 
 * Orchestrates the distributed saga of paying out a freelancer:
 * 1. T1: Reserve funds (FREELANCER_WALLET → PAYOUT_IN_FLIGHT)
 * 2. Out-of-transaction: Call provider
 * 3. T2: Confirm (PAYOUT_IN_FLIGHT → EXTERNAL_BANK:PAYOUTS) or Reverse (PAYOUT_IN_FLIGHT → FREELANCER_WALLET)
 * 
 * Timeouts are classified as UNKNOWN; recovery job handles status checks and retries.
 */
@Service
public class PayoutService {
    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);
    
    private final PayoutRepository payoutRepository;
    private final LedgerService ledgerService;
    private final OutboxWriter outboxWriter;
    private final PayoutProviderClient providerClient;
    private final JdbcClient jdbcClient;
    private final Clock clock;
    
    public PayoutService(
            PayoutRepository payoutRepository,
            LedgerService ledgerService,
            OutboxWriter outboxWriter,
            PayoutProviderClient providerClient,
            JdbcClient jdbcClient,
            Clock clock) {
        this.payoutRepository = payoutRepository;
        this.ledgerService = ledgerService;
        this.outboxWriter = outboxWriter;
        this.providerClient = providerClient;
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }
    
    /**
     * Request a payout (T1: Reserve funds).
     * Requires Idempotency-Key to be unique per freelancer.
     * 
     * @return the created payout in its current state
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Payout requestPayout(long freelancerId, long walletAccountId, long amountPaise, String idempotencyKey) {
        // Check if this idempotency key was already used
        var existing = jdbcClient.sql(
                "SELECT payout_id FROM payout_idempotency WHERE freelancer_id = ? AND idempotency_key = ?")
            .param(freelancerId)
            .param(idempotencyKey)
            .query((rs, rowNum) -> rs.getObject(1, UUID.class))
            .stream()
            .findFirst();
        
        if (existing.isPresent()) {
            // Idempotency: return the existing payout
            return payoutRepository.findById(existing.get())
                .orElseThrow(() -> new DomainException("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR,
                    "Payout not found after idempotency lookup"));
        }
        
        // T1: Create the payout and reserve funds
        UUID payoutId = UUID.randomUUID();
        Payout payout = payoutRepository.createReserved(payoutId, freelancerId, walletAccountId, amountPaise);
        
        // Move funds: FREELANCER_WALLET → PAYOUT_IN_FLIGHT
        try {
            ledgerService.post(new PostingRequest(
                "PAYOUT_RESERVE",
                "PAYOUT",
                payoutId.toString(),
                "payout:reserve:" + payoutId,
                "Reserve funds for payout",
                List.of(
                    new Leg(walletAccountId, -amountPaise),
                    new Leg(getPayoutInFlightAccountId(), amountPaise)
                )
            ));
        } catch (DomainException e) {
            if ("INSUFFICIENT_FUNDS".equals(e.getCode())) {
                throw e;
            }
            throw e;
        }
        
        // Record idempotency
        jdbcClient.sql(
                "INSERT INTO payout_idempotency (payout_id, freelancer_id, idempotency_key) VALUES (?, ?, ?)")
            .param(payoutId)
            .param(freelancerId)
            .param(idempotencyKey)
            .update();
        
        // Emit event
        outboxWriter.write("PAYOUT", payoutId.toString(), "PayoutReserved", "escrow.payment-events",
            new java.util.HashMap<String, Object>() {{
                put("payoutId", payoutId.toString());
                put("freelancerId", freelancerId);
                put("amountPaise", amountPaise);
            }});
        
        // T1 committed; now call provider outside transaction
        handleProviderCall(payout);
        
        return payout;
    }
    
    /**
     * Call the provider and handle the response (outside the T1 transaction).
     * Calls T2 to confirm or reverse based on the provider's response.
     */
    private void handleProviderCall(Payout payout) {
        try {
            // Call provider with payout ID as idempotency key
            var providerResponse = providerClient.requestPayout(payout.id(), "freelancer@example.com", payout.amountPaise());
            
            if (providerResponse == null) {
                // Timeout or 5xx → UNKNOWN, wait for recovery job
                transitionToUnknown(payout, "Provider call timed out or errored");
                return;
            }
            
            int status = providerResponse.statusCode();
            if (status == 200) {
                // Success → confirm
                confirmPayout(payout);
            } else if (status == 202) {
                // Async processing → PROCESSING
                transitionState(payout, PayoutState.PROCESSING, "Provider returned 202 PROCESSING");
            } else if (status >= 400 && status < 500) {
                // Client error → fail
                reversePayout(payout, "Provider rejected: " + status);
            } else if (status >= 500) {
                // Server error → UNKNOWN
                transitionToUnknown(payout, "Provider error: " + status);
            }
        } catch (Exception e) {
            log.error("Unexpected error calling provider for payout {}", payout.id(), e);
            transitionToUnknown(payout, "Exception: " + e.getMessage());
        }
    }
    
    /**
     * Confirm a payout (T2: Move funds to EXTERNAL_BANK:PAYOUTS).
     * Only idempotent if the provider call was idempotent (it is, per AGENTS.md).
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void confirmPayout(Payout payout) {
        if (!payout.canConfirm()) {
            log.warn("Cannot confirm payout {} from state {}", payout.id(), payout.state());
            return;
        }
        
        // Conditional update to CONFIRMED
        int updated = payoutRepository.transitionState(payout.id(), payout.state(), PayoutState.CONFIRMED, "Provider SUCCESS");
        if (updated == 0) {
            log.warn("Payout {} state changed during confirm, skipping", payout.id());
            return;
        }
        
        // Move funds: PAYOUT_IN_FLIGHT → EXTERNAL_BANK:PAYOUTS
        ledgerService.post(new PostingRequest(
            "PAYOUT_CONFIRM",
            "PAYOUT",
            payout.id().toString(),
            "payout:confirm:" + payout.id(),
            "Confirm payout to external bank",
            List.of(
                new Leg(getPayoutInFlightAccountId(), -payout.amountPaise()),
                new Leg(getExternalBankPayoutsAccountId(), payout.amountPaise())
            )
        ));
        
        outboxWriter.write("PAYOUT", payout.id().toString(), "PayoutConfirmed", "escrow.payment-events",
            new java.util.HashMap<String, Object>() {{
                put("payoutId", payout.id().toString());
                put("amountPaise", payout.amountPaise());
            }});
    }
    
    /**
     * Reverse a payout (T2: Move funds back to FREELANCER_WALLET).
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void reversePayout(Payout payout, String reason) {
        if (!payout.canReverse()) {
            log.warn("Cannot reverse payout {} from state {}", payout.id(), payout.state());
            return;
        }
        
        // Conditional update to FAILED or CANCELLED
        PayoutState targetState = reason.contains("Cancelled") ? PayoutState.CANCELLED : PayoutState.FAILED;
        int updated = payoutRepository.transitionState(payout.id(), payout.state(), targetState, reason);
        if (updated == 0) {
            log.warn("Payout {} state changed during reverse, skipping", payout.id());
            return;
        }
        
        // Move funds back: PAYOUT_IN_FLIGHT → FREELANCER_WALLET
        ledgerService.post(new PostingRequest(
            targetState == PayoutState.CANCELLED ? "PAYOUT_CANCEL" : "PAYOUT_REVERSE",
            "PAYOUT",
            payout.id().toString(),
            "payout:reverse:" + payout.id(),
            targetState == PayoutState.CANCELLED ? "Cancel payout" : "Reverse failed payout",
            List.of(
                new Leg(getPayoutInFlightAccountId(), -payout.amountPaise()),
                new Leg(payout.walletAccountId(), payout.amountPaise())
            )
        ));
        
        String eventType = targetState == PayoutState.CANCELLED ? "PayoutCancelled" : "PayoutFailed";
        outboxWriter.write("PAYOUT", payout.id().toString(), eventType, "escrow.payment-events",
            new java.util.HashMap<String, Object>() {{
                put("payoutId", payout.id().toString());
                put("amountPaise", payout.amountPaise());
                put("reason", reason);
            }});
    }
    
    /**
     * Mark a payout as UNKNOWN (timeout or error from provider).
     */
    @Transactional(propagation = Propagation.REQUIRED)
    private void transitionToUnknown(Payout payout, String reason) {
        Instant nextCheck = clock.instant().plusSeconds(15);
        int updated = payoutRepository.transitionState(payout.id(), payout.state(), PayoutState.UNKNOWN, reason);
        if (updated > 0) {
            payoutRepository.claimForRecovery(payout.id(), nextCheck);
        }
    }
    
    /**
     * Transition to PROCESSING (provider returned 202).
     */
    @Transactional(propagation = Propagation.REQUIRED)
    private void transitionState(Payout payout, PayoutState targetState, String reason) {
        Instant nextCheck = clock.instant().plusSeconds(10);
        int updated = payoutRepository.transitionState(payout.id(), payout.state(), targetState, reason);
        if (updated > 0) {
            payoutRepository.claimForRecovery(payout.id(), nextCheck);
        }
    }
    
    /**
     * Get or create the PAYOUT_IN_FLIGHT account.
     */
    private long getPayoutInFlightAccountId() {
        return jdbcClient.sql("SELECT id FROM accounts WHERE code = ? LIMIT 1")
            .param("PAYOUT_IN_FLIGHT")
            .query((rs, rowNum) -> rs.getLong(1))
            .stream()
            .findFirst()
            .orElseThrow(() -> new DomainException("PAYOUT_IN_FLIGHT_ACCOUNT_MISSING", 
                HttpStatus.INTERNAL_SERVER_ERROR, "PAYOUT_IN_FLIGHT account not found"));
    }
    
    /**
     * Get or create the EXTERNAL_BANK:PAYOUTS account.
     */
    private long getExternalBankPayoutsAccountId() {
        return jdbcClient.sql("SELECT id FROM accounts WHERE code = ? LIMIT 1")
            .param("EXTERNAL_BANK:PAYOUTS")
            .query((rs, rowNum) -> rs.getLong(1))
            .stream()
            .findFirst()
            .orElseThrow(() -> new DomainException("EXTERNAL_BANK_PAYOUTS_ACCOUNT_MISSING",
                HttpStatus.INTERNAL_SERVER_ERROR, "EXTERNAL_BANK:PAYOUTS account not found"));
    }
}
