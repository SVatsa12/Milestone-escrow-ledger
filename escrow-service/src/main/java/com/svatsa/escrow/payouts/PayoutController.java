package com.svatsa.escrow.payouts;

import com.svatsa.escrow.common.DomainException;
import com.svatsa.escrow.common.ValidationException;
import com.svatsa.escrow.ledger.AccountRepository;
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
    private final AccountRepository accountRepository;

    public PayoutController(PayoutService payoutService, AccountRepository accountRepository) {
        this.payoutService = payoutService;
        this.accountRepository = accountRepository;
    }

    public record PayoutApiRequest(long amountPaise) {}

    @PostMapping
    public ResponseEntity<Map<String, Object>> requestPayout(
        @RequestHeader(value = "X-Actor-Id", required = false) Long actorId,
        @RequestHeader(value = "X-Actor-Role", required = false) String actorRole,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestBody PayoutApiRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ValidationException("Idempotency-Key header is required");
        }
        if (actorId == null || !"FREELANCER".equals(actorRole)) {
            throw new DomainException("FORBIDDEN", HttpStatus.FORBIDDEN, "Only a freelancer can request a payout");
        }
        if (request.amountPaise() <= 0) {
            throw new ValidationException("amountPaise must be positive");
        }
        long walletId = accountRepository.findByCode("FREELANCER_WALLET:" + actorId)
            .orElseThrow(() -> new DomainException("NOT_FOUND", HttpStatus.NOT_FOUND, "Freelancer wallet not found"))
            .id();

        Payout payout = payoutService.requestPayout(actorId, walletId, request.amountPaise(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
            "payoutId", payout.id().toString(),
            "state", payout.state().name(),
            "amountPaise", payout.amountPaise()));
    }
}
