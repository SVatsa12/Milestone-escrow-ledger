package com.svatsa.escrow.dispute;

import com.svatsa.escrow.common.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for dispute operations.
 * All money-moving endpoints require Idempotency-Key header.
 */
@RestController
@RequestMapping("/api/escrow/disputes")
public class DisputeController {

    private final DisputeService disputeService;
    private final DisputeRepository disputeRepository;

    public DisputeController(DisputeService disputeService, DisputeRepository disputeRepository) {
        this.disputeService = disputeService;
        this.disputeRepository = disputeRepository;
    }

    /**
     * Opens a dispute on a milestone.
     * POST /api/escrow/disputes
     * Body: {milestoneId, reason}
     * Header: X-Actor-Id, X-Actor-Role, Idempotency-Key
     */
    @PostMapping
    public ResponseEntity<Dispute> openDispute(
        Actor actor,
        @RequestBody OpenDisputeRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        if (request.milestoneId() == null || request.reason() == null || request.reason().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Dispute dispute = disputeService.openDispute(
            actor,
            request.milestoneId(),
            request.reason(),
            "dispute-freeze:milestone:" + request.milestoneId()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(dispute);
    }

    /**
     * Resolves a dispute (ADMIN only).
     * POST /api/escrow/disputes/{id}/resolve
     * Body: {freelancerSharePaise, clientSharePaise, resolutionNote}
     * Header: X-Actor-Id, X-Actor-Role, Idempotency-Key
     */
    @PostMapping("/{id}/resolve")
    public ResponseEntity<Void> resolveDispute(
        Actor actor,
        @PathVariable Long id,
        @RequestBody ResolvDisputeRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        if (request.freelancerSharePaise() == null || request.clientSharePaise() == null) {
            return ResponseEntity.badRequest().build();
        }

        disputeService.resolveDispute(
            actor,
            id,
            request.freelancerSharePaise(),
            request.clientSharePaise(),
            request.resolutionNote() != null ? request.resolutionNote() : "",
            "dispute-resolve:" + id
        );

        return ResponseEntity.ok().build();
    }

    /**
     * Gets a dispute by ID.
     * GET /api/escrow/disputes/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<Dispute> getDispute(@PathVariable Long id) {
        return disputeRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Request DTOs
     */
    public record OpenDisputeRequest(Long milestoneId, String reason) {}

    public record ResolvDisputeRequest(
        Long freelancerSharePaise,
        Long clientSharePaise,
        String resolutionNote
    ) {}
}
