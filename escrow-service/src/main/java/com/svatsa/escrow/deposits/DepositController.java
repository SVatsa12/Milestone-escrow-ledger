package com.svatsa.escrow.deposits;

import com.svatsa.escrow.common.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for deposits.
 * 
 * Endpoints:
 * - POST /api/v1/deposits - initiate a deposit (requires Idempotency-Key)
 * - GET /api/v1/deposits/{id} - check deposit status
 */
@RestController
@RequestMapping("/api/v1/deposits")
public class DepositController {

    private final DepositService depositService;

    public DepositController(DepositService depositService) {
        this.depositService = depositService;
    }

    /**
     * POST /api/v1/deposits
     * Initiate a new deposit (top-up wallet).
     * 
     * Requires:
     * - X-Actor-Id header: client user ID
     * - X-Actor-Role header: must be CLIENT
     * - Idempotency-Key header: for idempotency
     * 
     * Body: { "amountPaise": 10000 }
     * 
     * Returns:
     * - 200 with deposit details (depositId, razorpayOrderId, keyId, amountPaise)
     * - 400 if validation fails (amount <= 0)
     * - 403 if not a CLIENT
     */
    @PostMapping
    public ResponseEntity<?> initiateDeposit(
        @RequestHeader("X-Actor-Id") Long actorId,
        @RequestHeader("X-Actor-Role") String actorRole,
        @RequestBody InitiateDepositRequest request
    ) {
        // Authorization check
        if (!"CLIENT".equals(actorRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            DepositService.DepositResponse response = depositService.initiateDeposit(
                actorId,
                request.amountPaise()
            );
            return ResponseEntity.ok(response);
        } catch (ValidationException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(e.getCode(), e.getMessage()));
        }
    }

    /**
     * GET /api/v1/deposits/{id}
     * Check the status of a deposit.
     * 
     * Returns:
     * - 200 with deposit details (including status: CREATED, CAPTURED, FAILED, AMOUNT_MISMATCH)
     * - 404 if not found
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getDeposit(@PathVariable Long id) {
        return depositService.getDeposit(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Request body for initiating a deposit.
     */
    public record InitiateDepositRequest(
        long amountPaise
    ) {}

    /**
     * Error response DTO.
     */
    public record ErrorResponse(
        String code,
        String message
    ) {}
}
