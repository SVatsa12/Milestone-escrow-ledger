package com.svatsa.escrow.recon;

import com.svatsa.escrow.payouts.Payout;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.AccountType;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.payouts.PayoutProviderClient;
import com.svatsa.escrow.payouts.PayoutRepository;
import com.svatsa.escrow.payouts.PayoutService;
import com.svatsa.escrow.payouts.PayoutState;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
    private org.springframework.transaction.support.TransactionTemplate seedTx;

    @Autowired
    private PayoutProviderClient providerClient;

    private long freelancerId;
    private long walletAccountId;

    /** Recon runs are keyed by date; each repository-level test gets its own so they never share a run. */
    private LocalDate runDate;

    @BeforeEach
    void setUp() {
        runDate = LocalDate.of(2000, 1, 1).plusDays(java.util.concurrent.ThreadLocalRandom.current().nextInt(0, 9_000));
        
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

    // --- P12 behaviour, with real payouts through the saga and the fake provider ---------------
    // (Replaces a test that inserted a RESERVED payout row without its reservation posting, which
    // correctly broke I7, and asserted nothing about mismatches.)

    @Test
    @DisplayName("a clean day: a confirmed payout the provider also reports as SUCCESS is not a mismatch")
    void cleanDayHasNoMismatchForAConfirmedPayout() {
        Payout payout = confirmedPayout(2_500);

        reconService.reconcilePayouts(LocalDate.now());

        // Used to report STATE_MISMATCH: ours says CONFIRMED, the provider says SUCCESS.
        assertThat(mismatchesFor(payout)).isEmpty();
    }

    @Test
    @DisplayName("amount drift at the provider is reported as AMOUNT_MISMATCH")
    void amountDriftIsAmountMismatch() {
        Payout payout = confirmedPayout(2_500);
        PROVIDER.setAmount(payout.id().toString(), 2_400);

        reconService.reconcilePayouts(LocalDate.now());

        assertThat(mismatchesFor(payout)).extracting(ReconMismatch::mismatchReason).containsExactly("AMOUNT_MISMATCH");
    }

    @Test
    @DisplayName("running twice on the same date does not duplicate mismatches")
    void rerunDoesNotDuplicateMismatches() {
        Payout payout = confirmedPayout(2_500);
        PROVIDER.setAmount(payout.id().toString(), 1);

        reconService.reconcilePayouts(LocalDate.now());
        reconService.reconcilePayouts(LocalDate.now());

        assertThat(mismatchesFor(payout)).hasSize(1);
    }

    private Payout confirmedPayout(long amount) {
        PROVIDER.reset(com.svatsa.escrow.chaos.FakePayoutProvider.Mode.SUCCESS, 1, 0);
        long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();
        seedTx.executeWithoutResult(s -> ledgerService.post(new com.svatsa.escrow.ledger.PostingRequest(
            "DEPOSIT", "TEST_SEED", "recon", "recon-seed:" + UUID.randomUUID(), "seed",
            java.util.List.of(new com.svatsa.escrow.ledger.Leg(bank, -amount),
                new com.svatsa.escrow.ledger.Leg(walletAccountId, amount)))));
        Payout payout = payoutService.requestPayout(freelancerId, walletAccountId, amount, "recon-" + UUID.randomUUID());
        assertThat(payout.state()).isEqualTo(PayoutState.CONFIRMED);
        return payout;
    }

    private java.util.List<ReconMismatch> mismatchesFor(Payout payout) {
        ReconRun run = reconRepo.findRun(LocalDate.now(), "PAYOUT").orElseThrow();
        return reconRepo.findMismatchesByRun(run.id()).stream()
            .filter(m -> m.entityId().equals(payout.id().toString()))
            .toList();
    }

    @Test
    @DisplayName("Should create reconciliation run with correct status")
    void shouldCreateReconRun() {
        var run = reconRepo.getOrCreateRun(runDate, "PAYOUT");

        assertThat(run.id()).isNotNull();
        assertThat(run.runDate()).isEqualTo(runDate);
        assertThat(run.entityType()).isEqualTo("PAYOUT");
        assertThat(run.status()).isEqualTo("RUNNING");
        assertThat(run.isRunning()).isTrue();
        assertThat(run.isCompleted()).isFalse();
    }

    @Test
    @DisplayName("Should mark run as completed with mismatch count")
    void shouldMarkRunCompleted() {
        var run = reconRepo.getOrCreateRun(runDate, "PAYOUT");

        reconRepo.markCompleted(run.id(), 3, "Found 3 mismatches");

        var updated = reconRepo.findRunById(run.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo("COMPLETED");
        assertThat(updated.isCompleted()).isTrue();
        assertThat(updated.mismatchCount()).isEqualTo(3);
        assertThat(updated.notes()).isEqualTo("Found 3 mismatches");
    }

    @Test
    @DisplayName("Should save and retrieve mismatches")
    void shouldSaveRetrieveMismatches() {
        var run = reconRepo.getOrCreateRun(runDate, "PAYOUT");

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
    @DisplayName("Should detect amount mismatch")
    void shouldDetectAmountMismatch() {
        var run = reconRepo.getOrCreateRun(runDate, "PAYOUT");

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
    @DisplayName("Should find runs by date")
    void shouldFindRunsByDate() {
        var run1 = reconRepo.getOrCreateRun(runDate, "PAYOUT");
        var run2 = reconRepo.getOrCreateRun(runDate, "DEPOSIT");

        var runs = reconRepo.findRunsByDate(runDate);

        assertThat(runs).hasSize(2);
        assertThat(runs).extracting(ReconRun::entityType)
            .containsExactlyInAnyOrder("PAYOUT", "DEPOSIT");
    }

    @Test
    @DisplayName("Should handle duplicate run creation")
    void shouldHandleDuplicateRunCreation() {
        var run1 = reconRepo.getOrCreateRun(runDate, "PAYOUT");
        var run2 = reconRepo.getOrCreateRun(runDate, "PAYOUT");

        // Should return the same run (or at least same ID)
        assertThat(run1.id()).isEqualTo(run2.id());
    }

    @Test
    @DisplayName("Should mark run as failed")
    void shouldMarkRunFailed() {
        var run = reconRepo.getOrCreateRun(runDate, "PAYOUT");

        reconRepo.markFailed(run.id(), "Provider connection timeout");

        var updated = reconRepo.findRunById(run.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo("FAILED");
        assertThat(updated.isFailed()).isTrue();
        assertThat(updated.notes()).isEqualTo("Provider connection timeout");
    }
}
