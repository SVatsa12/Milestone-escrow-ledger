package com.svatsa.escrow.recon;

import com.svatsa.escrow.test.ProviderControl;
import com.svatsa.escrow.test.Actors;
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
 * Reconciliation against the real payout provider (PAYOUT_PROVIDER, via ProviderControl) and
 * Razorpay (RAZORPAY, FakeRazorpayGateway), plus the recon repository.
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

    // --- Reconciliation against the real provider and Razorpay (FakeRazorpayGateway) ---------------
    //
    // Every test reconciles its own Asia/Kolkata day in 2031, four days apart (more than the ±1 day
    // matching window), with the clock (shared by escrow and the in-process provider) set to that
    // day. Other test classes create payouts and deposits "today", so they never land on these days,
    // and a clean day really means zero mismatches.

    private static final java.util.concurrent.atomic.AtomicInteger DAYS = new java.util.concurrent.atomic.AtomicInteger();

    @Autowired private com.svatsa.escrow.deposits.FakeRazorpayGateway razorpay;
    @Autowired private com.svatsa.escrow.deposits.DepositService depositService;
    @Autowired private com.svatsa.escrow.deposits.DepositWebhookService webhookService;
    @Autowired private com.svatsa.escrow.escrow.UserRepository userRepository;
    @Autowired private com.svatsa.escrow.payouts.PayoutRecoveryJob recoveryJob;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    /** A day nobody else uses, with the clock at 11:30 IST on it. */
    private LocalDate reconDay() {
        LocalDate day = LocalDate.of(2031, 1, 1).plusDays(4L * DAYS.getAndIncrement());
        clock.set(istTime(day, 11, 30, 0));
        razorpay.reset();
        return day;
    }

    private static java.time.Instant istTime(LocalDate day, int h, int m, int s) {
        return day.atTime(h, m, s).atZone(ReconciliationService.IST).toInstant();
    }

    @Test
    @DisplayName("clean day: a confirmed payout and a captured deposit that both sides agree on → 0 mismatches")
    void cleanDayHasNoMismatches() throws Exception {
        LocalDate day = reconDay();
        confirmedPayout(2_500);
        Deposit d = capturedDeposit(40_000);
        razorpay.addFakePayment(d.paymentId(), d.orderId(), 40_000, "captured", clock.instant());
        razorpay.addFakePayment("pay_failed_" + UUID.randomUUID(), "order_x", 999, "failed", clock.instant());   // ignored

        reconService.run(day);

        assertThat(run(day, "PAYOUT").status()).isEqualTo("COMPLETED");
        assertThat(run(day, "DEPOSIT").status()).isEqualTo("COMPLETED");
        assertThat(mismatches(day, "PAYOUT")).isEmpty();
        assertThat(mismatches(day, "DEPOSIT")).isEmpty();
        assertThat(run(day, "PAYOUT").notes()).isEqualTo("PAYOUT_PROVIDER clean");
    }

    @Test
    @DisplayName("PAYOUT_PROVIDER: a payout POSTed straight to the provider, unknown to escrow → MISSING_IN_LEDGER (ABSENT)")
    void strayProviderPayoutIsMissingInLedger() {
        LocalDate day = reconDay();
        String key = "stray-" + UUID.randomUUID();
        assertThat(PROVIDER.postPayout(key, "someone", 777).statusCode()).isEqualTo(200);

        reconService.reconcilePayouts(day);

        assertThat(mismatches(day, "PAYOUT")).singleElement().satisfies(m -> {
            assertThat(m.mismatchReason()).isEqualTo("MISSING_IN_LEDGER");
            assertThat(m.source()).isEqualTo("PAYOUT_PROVIDER");
            assertThat(m.entityId()).isEqualTo(key);
            assertThat(m.expectedState()).isEqualTo("ABSENT");
            assertThat(m.actualAmountPaise()).isEqualTo(777);
        });
    }

    /**
     * The provider paid (TIMEOUT_THEN_SUCCEED: it paid, escrow timed out and has UNKNOWN), and then
     * escrow wrongly decided the payout FAILED. The task says "force the payout to FAILED in SQL";
     * an UPDATE alone would leave its money in PAYOUT_IN_FLIGHT with no reverse entry and break I7
     * and I8 (the @AfterEach check fails the test). The double payout this detail is about is "the
     * bank paid AND we refunded the wallet", so the test reverses it through PayoutService, which
     * refunds the wallet exactly like a real (wrong) FAILED decision would.
     */
    @Test
    @DisplayName("PAYOUT_PROVIDER: the provider paid but escrow marked the payout FAILED → MISSING_IN_LEDGER, detail FAILED (double payout)")
    void paidButFailedIsADoublePayout() {
        LocalDate day = reconDay();
        PROVIDER.setMode(ProviderControl.Mode.TIMEOUT_THEN_SUCCEED, 2_500L, null, 1L);
        long[] wallet = fundedFreelancer(5_000);
        Payout p = payoutService.requestPayout(Actors.freelancer(wallet[0]), wallet[0], wallet[1], 3_000, "recon-" + UUID.randomUUID());
        assertThat(p.state()).isEqualTo(PayoutState.UNKNOWN);
        assertThat(payoutService.reversePayout(p, PayoutState.FAILED, "FAILED", "wrongly failed (test)")).isTrue();
        assertThat(accountRepository.findById(wallet[1]).orElseThrow().balance()).as("wallet refunded").isEqualTo(5_000);

        reconService.reconcilePayouts(day);

        assertThat(mismatches(day, "PAYOUT")).singleElement().satisfies(m -> {
            assertThat(m.mismatchReason()).isEqualTo("MISSING_IN_LEDGER");
            assertThat(m.entityId()).isEqualTo(p.id().toString());
            assertThat(m.expectedState()).isEqualTo("FAILED");
            assertThat(m.detail()).contains("DOUBLE PAYOUT").contains("FAILED");
        });
    }

    @Test
    @DisplayName("PAYOUT_PROVIDER: amount drift at the provider → AMOUNT_MISMATCH")
    void providerAmountDriftIsAmountMismatch() {
        LocalDate day = reconDay();
        Payout payout = confirmedPayout(2_500);
        providerAmountDrifts(payout, 2_400);

        reconService.reconcilePayouts(day);

        assertThat(mismatches(day, "PAYOUT")).singleElement().satisfies(m -> {
            assertThat(m.mismatchReason()).isEqualTo("AMOUNT_MISMATCH");
            assertThat(m.expectedAmountPaise()).isEqualTo(2_500);
            assertThat(m.actualAmountPaise()).isEqualTo(2_400);
        });
    }

    @Test
    @DisplayName("PAYOUT_PROVIDER: confirmed by us, no settlement at the provider → MISSING_AT_PROVIDER")
    void confirmedButNotPaidIsMissingAtProvider() {
        LocalDate day = reconDay();
        Payout payout = confirmedPayout(1_200);
        jdbcClient.sql("DELETE FROM provider.payouts WHERE idempotency_key = ?").param(payout.id().toString()).update();

        reconService.reconcilePayouts(day);

        assertThat(mismatches(day, "PAYOUT")).singleElement().satisfies(m -> {
            assertThat(m.mismatchReason()).isEqualTo("MISSING_AT_PROVIDER");
            assertThat(m.entityId()).isEqualTo(payout.id().toString());
            assertThat(m.actualState()).isEqualTo("NOT_FOUND");
        });
    }

    @Test
    @DisplayName("midnight: provider settles 23:59 IST, we confirm 00:01 the next day → no mismatch on either day")
    void settlementAcrossMidnightIsOneMatchNotTwoMismatches() {
        LocalDate day = reconDay();
        clock.set(istTime(day, 23, 59, 30));
        PROVIDER.setMode(ProviderControl.Mode.TIMEOUT_THEN_SUCCEED, 2_500L, null, 1L);
        long[] wallet = fundedFreelancer(5_000);
        Payout p = payoutService.requestPayout(Actors.freelancer(wallet[0]), wallet[0], wallet[1], 3_000, "recon-" + UUID.randomUUID());
        assertThat(p.state()).isEqualTo(PayoutState.UNKNOWN);

        clock.set(istTime(day.plusDays(1), 0, 1, 0));
        recoveryJob.runOnce();
        assertThat(payoutRepo.findById(p.id()).orElseThrow().state()).isEqualTo(PayoutState.CONFIRMED);
        // The two sides really are on different days.
        assertThat(PROVIDER.settlements(day)).extracting(ProviderControl.Record::idempotencyKey).contains(p.id().toString());
        assertThat(PROVIDER.settlements(day.plusDays(1))).isEmpty();

        reconService.reconcilePayouts(day);
        reconService.reconcilePayouts(day.plusDays(1));

        assertThat(mismatches(day, "PAYOUT")).as("the provider's day").isEmpty();
        assertThat(mismatches(day.plusDays(1), "PAYOUT")).as("our confirmation day").isEmpty();
    }

    @Test
    @DisplayName("re-running a date replaces its mismatches: a fixed one disappears, nothing duplicates")
    void rerunReplacesTheRunsMismatches() {
        LocalDate day = reconDay();
        Payout payout = confirmedPayout(2_500);
        providerAmountDrifts(payout, 1);

        reconService.reconcilePayouts(day);
        reconService.reconcilePayouts(day);
        assertThat(mismatches(day, "PAYOUT")).as("not duplicated").hasSize(1);
        assertThat(run(day, "PAYOUT").mismatchCount()).isEqualTo(1);

        providerAmountDrifts(payout, 2_500);   // the bank corrects its books
        reconService.reconcilePayouts(day);
        assertThat(mismatches(day, "PAYOUT")).as("replaced, not accumulated").isEmpty();
        assertThat(run(day, "PAYOUT").mismatchCount()).isZero();
    }

    @Test
    @DisplayName("RAZORPAY: captured at Razorpay but no webhook ever arrived → MISSING_IN_LEDGER")
    void missedWebhookIsMissingInLedger() {
        LocalDate day = reconDay();
        long client = newClient();
        var order = depositService.initiateDeposit(Actors.client(client), client, 25_000);
        String paymentId = "pay_" + UUID.randomUUID();
        razorpay.addFakePayment(paymentId, order.razorpayOrderId(), 25_000, "captured", clock.instant());

        reconService.reconcileDeposits(day);

        assertThat(mismatches(day, "DEPOSIT")).singleElement().satisfies(m -> {
            assertThat(m.mismatchReason()).isEqualTo("MISSING_IN_LEDGER");
            assertThat(m.source()).isEqualTo("RAZORPAY");
            assertThat(m.entityId()).isEqualTo(paymentId);
            assertThat(m.expectedState()).isEqualTo("CREATED");
            assertThat(m.detail()).contains("deposit " + order.depositId() + " is CREATED").contains("deposit:rzp:" + paymentId);
        });
    }

    @Test
    @DisplayName("RAZORPAY: credited by us, not captured at Razorpay → MISSING_AT_GATEWAY")
    void creditedButNotCapturedIsMissingAtGateway() throws Exception {
        LocalDate day = reconDay();
        Deposit d = capturedDeposit(15_000);

        reconService.reconcileDeposits(day);

        assertThat(mismatches(day, "DEPOSIT")).singleElement().satisfies(m -> {
            assertThat(m.mismatchReason()).isEqualTo("MISSING_AT_GATEWAY");
            assertThat(m.entityId()).isEqualTo(d.paymentId());
            assertThat(m.expectedAmountPaise()).isEqualTo(15_000);
        });
    }

    @Test
    @DisplayName("RAZORPAY: different amounts → AMOUNT_MISMATCH")
    void depositAmountMismatch() throws Exception {
        LocalDate day = reconDay();
        Deposit d = capturedDeposit(15_000);
        razorpay.addFakePayment(d.paymentId(), d.orderId(), 15_500, "captured", clock.instant());

        reconService.reconcileDeposits(day);

        assertThat(mismatches(day, "DEPOSIT")).singleElement().satisfies(m -> {
            assertThat(m.mismatchReason()).isEqualTo("AMOUNT_MISMATCH");
            assertThat(m.expectedAmountPaise()).isEqualTo(15_000);
            assertThat(m.actualAmountPaise()).isEqualTo(15_500);
        });
    }

    @Test
    @DisplayName("RAZORPAY: payment created 23:59:50 IST, captured by us after midnight → no mismatch on either day")
    void depositAcrossMidnight() throws Exception {
        LocalDate day = reconDay();
        clock.set(istTime(day.plusDays(1), 0, 0, 10));
        Deposit d = capturedDeposit(9_000);
        razorpay.addFakePayment(d.paymentId(), d.orderId(), 9_000, "captured", istTime(day, 23, 59, 50));

        reconService.reconcileDeposits(day);
        reconService.reconcileDeposits(day.plusDays(1));

        assertThat(mismatches(day, "DEPOSIT")).isEmpty();
        assertThat(mismatches(day.plusDays(1), "DEPOSIT")).isEmpty();
    }

    @Test
    @DisplayName("ledger invariant violations are recorded in the same run as LEDGER_INVARIANT")
    void invariantViolationsAreRecorded() {
        LocalDate day = reconDay();
        var account = accountRepository.create("RECON_CORRUPT:" + UUID.randomUUID(), AccountType.CLIENT_WALLET, null, false);
        // Deliberately break I3 (balance != sum of postings), then repair it before the @AfterEach check.
        jdbcClient.sql("UPDATE accounts SET balance = 5 WHERE id = ?").param(account.id()).update();
        try {
            reconService.reconcilePayouts(day);
        } finally {
            jdbcClient.sql("UPDATE accounts SET balance = 0 WHERE id = ?").param(account.id()).update();
        }

        assertThat(mismatches(day, "PAYOUT")).filteredOn(m -> m.mismatchReason().equals("LEDGER_INVARIANT"))
            .anySatisfy(m -> {
                assertThat(m.entityType()).isEqualTo("LEDGER");
                assertThat(m.detail()).startsWith("I3").contains(String.valueOf(account.id()));
            });
    }

    @Test
    @DisplayName("mismatch counts are exposed as escrow.recon.mismatches{source, reason}")
    void countsAreMetrics() {
        LocalDate day = reconDay();
        PROVIDER.postPayout("stray-a-" + UUID.randomUUID(), "x", 100);
        PROVIDER.postPayout("stray-b-" + UUID.randomUUID(), "x", 200);

        reconService.reconcilePayouts(day);

        assertThat(gauge("PAYOUT_PROVIDER", "MISSING_IN_LEDGER")).isEqualTo(2);
        assertThat(gauge("PAYOUT_PROVIDER", "AMOUNT_MISMATCH")).isZero();
        assertThat(meterRegistry.find("escrow.recon.runs").tags("source", "PAYOUT_PROVIDER", "status", "COMPLETED")
            .counter()).isNotNull();
    }

    // --- helpers ----------------------------------------------------------------------------------

    private record Deposit(long depositId, String orderId, String paymentId) {}

    private ReconRun run(LocalDate day, String entityType) {
        return reconRepo.findRun(day, entityType).orElseThrow();
    }

    private java.util.List<ReconMismatch> mismatches(LocalDate day, String entityType) {
        return reconRepo.findMismatchesByRun(run(day, entityType).id());
    }

    private double gauge(String source, String reason) {
        return meterRegistry.get("escrow.recon.mismatches").tags("source", source, "reason", reason).gauge().value();
    }

    /** A new freelancer wallet with {@code amount} in it: {freelancerId, walletAccountId}. */
    private long[] fundedFreelancer(long amount) {
        long id = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
        long wallet = accountRepository.create("FREELANCER_WALLET:" + id, AccountType.FREELANCER_WALLET, id, false).id();
        long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();
        seedTx.executeWithoutResult(s -> ledgerService.post(new com.svatsa.escrow.ledger.PostingRequest(
            "TEST_SEED", "TEST_SEED", "recon", "recon-seed:" + UUID.randomUUID(), "seed",
            java.util.List.of(new com.svatsa.escrow.ledger.Leg(bank, -amount), new com.svatsa.escrow.ledger.Leg(wallet, amount)))));
        return new long[]{id, wallet};
    }

    /** SUCCESS mode: confirmed by us and settled by the provider, both at the clock's time. */
    private Payout confirmedPayout(long amount) {
        long[] wallet = fundedFreelancer(amount);
        Payout payout = payoutService.requestPayout(Actors.freelancer(wallet[0]), wallet[0], wallet[1], amount, "recon-" + UUID.randomUUID());
        assertThat(payout.state()).isEqualTo(PayoutState.CONFIRMED);
        return payout;
    }

    private long newClient() {
        return userRepository.save("recon-client-" + UUID.randomUUID().toString().substring(0, 8), "CLIENT").id();
    }

    /** A deposit captured through the real webhook processing (credited to the wallet), at the clock's time. */
    private Deposit capturedDeposit(long amount) throws Exception {
        long client = newClient();
        var order = depositService.initiateDeposit(Actors.client(client), client, amount);
        String paymentId = "pay_" + UUID.randomUUID();
        String body = "{\"payment\":{\"entity\":{\"id\":\"" + paymentId + "\",\"order_id\":\"" + order.razorpayOrderId()
            + "\",\"amount\":" + amount + "}}}";
        webhookService.process("evt_" + UUID.randomUUID(), "payment.captured",
            body.getBytes(java.nio.charset.StandardCharsets.UTF_8), json.readTree(body));
        return new Deposit(order.depositId(), order.razorpayOrderId(), paymentId);
    }

    /** The bank's books change: set the amount it holds for this payout, straight in its schema. */
    private void providerAmountDrifts(Payout payout, long amountPaise) {
        int rows = jdbcClient.sql("UPDATE provider.payouts SET amount_paise = ? WHERE idempotency_key = ?")
            .param(amountPaise).param(payout.id().toString()).update();
        assertThat(rows).as("the provider has a record for " + payout.id()).isEqualTo(1);
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
