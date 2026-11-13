package com.svatsa.escrow.recon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin endpoints for reconciliation and invariant checking.
 *
 * Requires: X-Actor-Role: ADMIN header
 * All endpoints are under /api/v1/admin/
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final ReconciliationService reconService;
    private final ReconciliationRepository reconRepo;
    private final ReconciliationScheduler reconScheduler;
    private final InvariantChecker invariantChecker;

    public AdminController(
        ReconciliationService reconService,
        ReconciliationRepository reconRepo,
        ReconciliationScheduler reconScheduler,
        InvariantChecker invariantChecker
    ) {
        this.reconService = reconService;
        this.reconRepo = reconRepo;
        this.reconScheduler = reconScheduler;
        this.invariantChecker = invariantChecker;
    }

    /**
     * GET /api/v1/admin/invariants
     * Run invariant checks and return any violations.
     */
    @GetMapping("/invariants")
    public ResponseEntity<?> getInvariants() {
        try {
            List<Violation> violations = invariantChecker.check();
            Map<String, Object> response = new HashMap<>();
            response.put("violations_count", violations.size());
            response.put("violations", violations);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error checking invariants", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "INVARIANT_CHECK_FAILED");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * POST /api/v1/admin/recon/run?date=YYYY-MM-DD
     * Trigger a reconciliation run for a specific date.
     */
    @PostMapping("/recon/run")
    public ResponseEntity<?> triggerReconRun(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        try {
            log.info("Admin triggered reconciliation for date={}", date);
            reconScheduler.runManual(date);
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Reconciliation started for date " + date);
            response.put("date", date);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error triggering reconciliation", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "RECON_TRIGGER_FAILED");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * GET /api/v1/admin/recon/runs?date=YYYY-MM-DD
     * Get all reconciliation runs for a date.
     */
    @GetMapping("/recon/runs")
    public ResponseEntity<?> getReconRuns(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        try {
            LocalDate queryDate = (date != null) ? date : LocalDate.now();
            List<ReconRun> runs = reconRepo.findRunsByDate(queryDate);

            Map<String, Object> response = new HashMap<>();
            response.put("date", queryDate);
            response.put("runs_count", runs.size());
            response.put("runs", runs.stream()
                .map(run -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", run.id());
                    m.put("entity_type", run.entityType());
                    m.put("status", run.status());
                    m.put("started_at", run.startedAt());
                    m.put("completed_at", run.completedAt());
                    m.put("mismatch_count", run.mismatchCount());
                    m.put("notes", run.notes());
                    return m;
                })
                .toList());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching recon runs", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "FETCH_RUNS_FAILED");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * GET /api/v1/admin/recon/runs/{id}/mismatches
     * Get all mismatches for a specific reconciliation run.
     */
    @GetMapping("/recon/runs/{id}/mismatches")
    public ResponseEntity<?> getReconMismatches(@PathVariable UUID id) {
        try {
            var run = reconRepo.findRunById(id).orElseThrow(() ->
                new IllegalArgumentException("Recon run not found: " + id)
            );

            List<ReconMismatch> mismatches = reconRepo.findMismatchesByRun(id);

            Map<String, Object> response = new HashMap<>();
            response.put("run_id", run.id());
            response.put("date", run.runDate());
            response.put("entity_type", run.entityType());
            response.put("status", run.status());
            response.put("mismatches_count", mismatches.size());
            response.put("mismatches", mismatches.stream()
                .map(m -> {
                    Map<String, Object> mm = new HashMap<>();
                    mm.put("id", m.id());
                    mm.put("entity_id", m.entityId());
                    mm.put("entity_type", m.entityType());
                    mm.put("expected_state", m.expectedState());
                    mm.put("actual_state", m.actualState());
                    mm.put("expected_amount_paise", m.expectedAmountPaise());
                    mm.put("actual_amount_paise", m.actualAmountPaise());
                    mm.put("mismatch_reason", m.mismatchReason());
                    mm.put("severity", m.severity());
                    mm.put("action_taken", m.actionTaken());
                    mm.put("created_at", m.createdAt());
                    return mm;
                })
                .toList());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "NOT_FOUND");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        } catch (Exception e) {
            log.error("Error fetching mismatches", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "FETCH_MISMATCHES_FAILED");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
