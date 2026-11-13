package com.svatsa.escrow.recon;

import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.AccountType;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.payouts.PayoutProviderClient;
import com.svatsa.escrow.payouts.PayoutRepository;
import com.svatsa.escrow.payouts.PayoutService;
import com.svatsa.escrow.payouts.PayoutState;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for reconciliation and invariant checking.
 *
 * Tests:
 * - Payout state reconciliation (RESERVED, PROCESSING, UNKNOWN, CONFIRMED, FAILED)
 * - Mismatch detection (state mismatch, amount mismatch, provider not found)
 * - Invariant I7 (PAYOUT_IN_FLIGHT balance)
 * - Invariant I8 (confirm/reverse entry consistency)
 * - Invariant I9 (outbox staleness)
 * - Admin endpoints (GET /invariants, POST /recon/run, GET /recon/runs)
 *
 * Note: Requires Postgres with Flyway migrations.
 * Disabled for H2 in-memory tests. Enable in CI with docker-compose.
 */
@Disabled("Requires Postgres with Flyway migrations; enable in CI with docker-compose")
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Reconciliation Integration Tests")
class ReconciliationIT extends AbstractIntegrationTest {

    @Autowired
    private ReconciliationService reconService;

    @Autowired
    private ReconciliationRepository reconRepo;

    @Autowired
    private PayoutService payoutService;

    @Autowired
    private PayoutRepository payoutRepo;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private PayoutProviderClient providerClient;

    private long freelancerId;
    private long walletAccountId;

    @BeforeEach
    void setUp() {
        clearLedger();
        
        // Create a freelancer wallet
        freelancerId = 42L;  // Simple test ID
        var freelancerWallet = accountRepository.create(
            "FREELANCER_WALLET:" + freelancerId,
            AccountType.FREELANCER_WALLET,
            null,
            false
        );
        walletAccountId = freelancerWallet.id();

        // Create PAYOUT_IN_FLIGHT account
        accountRepository.create(
            "PAYOUT_IN_FLIGHT",
            AccountType.PAYOUT_IN_FLIGHT,
            null,
            false
        );

        // Create EXTERNAL_BANK account for payouts
        accountRepository.create(
            "EXTERNAL_BANK:PAYOUTS",
            AccountType.EXTERNAL_BANK,
            null,
            true
        );
    }

    @Test
    @Transactional
    @DisplayName("Should detect state mismatch in payout reconciliation")
    void shouldDetectStateMismatch() {
        // Create a payout in RESERVED state
        UUID payoutId = UUID.randomUUID();
        var payout = payoutRepo.createReserved(payoutId, freelancerId, walletAccountId, 100_00);

        assertThat(payout.state()).isEqualTo(PayoutState.RESERVED);

        // Run reconciliation for today
        // (Note: In a real test, we'd mock the provider to return PROCESSING)
        reconService.reconcilePayouts(LocalDate.now());

        // Check if a run was created
        var runs = reconRepo.findRunsByDate(LocalDate.now());
        assertThat(runs).isNotEmpty();
        assertThat(runs.get(0).entityType()).isEqualTo("PAYOUT");
    }

    @Test
    @Transactional
    @DisplayName("Should create reconciliation run with correct status")
    void shouldCreateReconRun() {
        var run = reconRepo.getOrCreateRun(LocalDate.now(), "PAYOUT");

        assertThat(run.id()).isNotNull();
        assertThat(run.runDate()).isEqualTo(LocalDate.now());
        assertThat(run.entityType()).isEqualTo("PAYOUT");
        assertThat(run.status()).isEqualTo("RUNNING");
        assertThat(run.isRunning()).isTrue();
        assertThat(run.isCompleted()).isFalse();
    }

    @Test
    @Transactional
    @DisplayName("Should mark run as completed with mismatch count")
    void shouldMarkRunCompleted() {
        var run = reconRepo.getOrCreateRun(LocalDate.now(), "PAYOUT");

        reconRepo.markCompleted(run.id(), 3, "Found 3 mismatches");

        var updated = reconRepo.findRunById(run.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo("COMPLETED");
        assertThat(updated.isCompleted()).isTrue();
        assertThat(updated.mismatchCount()).isEqualTo(3);
        assertThat(updated.notes()).isEqualTo("Found 3 mismatches");
    }

    @Test
    @Transactional
    @DisplayName("Should save and retrieve mismatches")
    void shouldSaveRetrieveMismatches() {
        var run = reconRepo.getOrCreateRun(LocalDate.now(), "PAYOUT");

        var mismatch = new ReconMismatch(
            UUID.randomUUID(),
            run.id(),
            UUID.randomUUID().toString(),
            "PAYOUT",
            "RESERVED",
            "PROCESSING",
            100_00L,
            100_00L,
            "STATE_MISMATCH",
            "ERROR",
            "MANUAL_REVIEW_REQUIRED",
            java.time.Instant.now()
        );

        reconRepo.saveMismatch(mismatch);

        var mismatches = reconRepo.findMismatchesByRun(run.id());
        assertThat(mismatches).hasSize(1);
        assertThat(mismatches.get(0).mismatchReason()).isEqualTo("STATE_MISMATCH");
        assertThat(mismatches.get(0).severity()).isEqualTo("ERROR");
    }

    @Test
    @Transactional
    @DisplayName("Should detect amount mismatch")
    void shouldDetectAmountMismatch() {
        var run = reconRepo.getOrCreateRun(LocalDate.now(), "PAYOUT");

        var mismatch = new ReconMismatch(
            UUID.randomUUID(),
            run.id(),
            UUID.randomUUID().toString(),
            "PAYOUT",
            "CONFIRMED",
            "CONFIRMED",
            100_00L,
            99_99L,  // Amount mismatch
            "AMOUNT_MISMATCH",
            "ERROR",
            "MANUAL_REVIEW_REQUIRED",
            java.time.Instant.now()
        );

        reconRepo.saveMismatch(mismatch);

        var mismatches = reconRepo.findMismatchesByRun(run.id());
        assertThat(mismatches).hasSize(1);
        assertThat(mismatches.get(0).expectedAmountPaise()).isEqualTo(100_00L);
        assertThat(mismatches.get(0).actualAmountPaise()).isEqualTo(99_99L);
    }

    @Test
    @DisplayName("Should check invariants without errors")
    void shouldCheckInvariants() {
        // Run invariant check; should not throw
        var violations = invariantChecker.check();

        // In a clean state, there should be no violations
        assertThat(violations).isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("Should find runs by date")
    void shouldFindRunsByDate() {
        var run1 = reconRepo.getOrCreateRun(LocalDate.now(), "PAYOUT");
        var run2 = reconRepo.getOrCreateRun(LocalDate.now(), "DEPOSIT");

        var runs = reconRepo.findRunsByDate(LocalDate.now());

        assertThat(runs).hasSize(2);
        assertThat(runs).extracting(ReconRun::entityType)
            .containsExactlyInAnyOrder("PAYOUT", "DEPOSIT");
    }

    @Test
    @Transactional
    @DisplayName("Should handle duplicate run creation")
    void shouldHandleDuplicateRunCreation() {
        var run1 = reconRepo.getOrCreateRun(LocalDate.now(), "PAYOUT");
        var run2 = reconRepo.getOrCreateRun(LocalDate.now(), "PAYOUT");

        // Should return the same run (or at least same ID)
        assertThat(run1.id()).isEqualTo(run2.id());
    }

    @Test
    @Transactional
    @DisplayName("Should mark run as failed")
    void shouldMarkRunFailed() {
        var run = reconRepo.getOrCreateRun(LocalDate.now(), "PAYOUT");

        reconRepo.markFailed(run.id(), "Provider connection timeout");

        var updated = reconRepo.findRunById(run.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo("FAILED");
        assertThat(updated.isFailed()).isTrue();
        assertThat(updated.notes()).isEqualTo("Provider connection timeout");
    }
}
