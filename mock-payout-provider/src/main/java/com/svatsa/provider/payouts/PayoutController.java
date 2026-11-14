package com.svatsa.provider.payouts;

import com.svatsa.provider.payouts.PayoutService.Reply;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The bank's public API, the one escrow-service's PayoutProviderClient calls. */
@RestController
public class PayoutController {

    public static final String IDEMPOTENCY_HEADER = "X-Payout-Idempotency-Key";

    private final PayoutService service;

    public PayoutController(PayoutService service) {
        this.service = service;
    }

    public record PayoutRequest(String beneficiary, Long amountPaise) {}

    @PostMapping("/v1/payouts")
    public ResponseEntity<Object> requestPayout(
        @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
        @RequestBody PayoutRequest request
    ) {
        if (key == null || key.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_FAILED", "detail", IDEMPOTENCY_HEADER + " is required"));
        }
        if (request.amountPaise() == null || request.amountPaise() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_FAILED", "detail", "amountPaise must be positive"));
        }
        return respond(service.requestPayout(key, request.beneficiary(), request.amountPaise()));
    }

    @GetMapping("/v1/payouts/by-key/{key}")
    public ResponseEntity<Object> byKey(@PathVariable String key) {
        return respond(service.findByKey(key));
    }

    @PostMapping("/v1/payouts/by-key/{key}/cancel")
    public ResponseEntity<Object> cancel(@PathVariable String key) {
        return respond(service.cancel(key));
    }

    /** SUCCESS payouts settled on that Asia/Kolkata day. */
    @GetMapping("/v1/settlements")
    public List<ProviderPayout> settlements(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.settlements(date);
    }

    private static ResponseEntity<Object> respond(Reply reply) {
        return ResponseEntity.status(reply.status()).body(reply.body());
    }
}
