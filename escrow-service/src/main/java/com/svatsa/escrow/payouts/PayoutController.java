package com.svatsa.escrow.payouts;

import com.svatsa.escrow.common.Actor;
import com.svatsa.escrow.common.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * POST /api/v1/payouts: a freelancer withdraws from their wallet (P11).
 * Returns 202 with the payout's current state; the saga may still be settling it.
 * Repeats with the same Idempotency-Key return the same payout and never call the provider again.
 */
@RestController
@RequestMapping("/api/v1/payouts")
public class PayoutController {

    private final PayoutService payoutService;

    public PayoutController(PayoutService payoutService) {
        this.payoutService = payoutService;
    }

    public record PayoutApiRequest(long amountPaise) {}

    @PostMapping
    public ResponseEntity<Map<String, Object>> requestPayout(
        Actor actor,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestBody PayoutApiRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ValidationException("Idempotency-Key header is required");
        }
        if (request.amountPaise() <= 0) {
            throw new ValidationException("amountPaise must be positive");
        }
        Payout payout = payoutService.requestPayoutFromOwnWallet(actor, request.amountPaise(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
            "payoutId", payout.id().toString(),
            "state", payout.state().name(),
            "amountPaise", payout.amountPaise()));
    }
}
