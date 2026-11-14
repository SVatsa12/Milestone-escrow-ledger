package com.svatsa.escrow.payouts;

import com.svatsa.escrow.test.Actors;
import com.svatsa.escrow.test.ProviderControl.Mode;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.AccountType;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.PostingRequest;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * P11: the payout saga against the real mock-payout-provider in every mode. The provider runs in
 * this JVM, behind Toxiproxy, and is driven only through {@code PROVIDER} (ProviderControl).
 */
@DisplayName("Payout saga (P11)")
class PayoutSagaIT extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000;

    /** The TIMEOUT_* modes' delay: just past escrow's 2s read timeout (AbstractIntegrationTest). */
    private static final long TIMEOUT_DELAY_MS = 2_500;

    /** ASYNC seeds (first draw after a mode change): 158 settles SUCCESS after 5.0s, 3187 FAILED after 5.08s. */
    private static final long ASYNC_SUCCESS_SEED = 158;
    private static final long ASYNC_FAILED_SEED = 3187;

    @Autowired private PayoutService payoutService;
    @Autowired private PayoutRepository payoutRepository;
    @Autowired private PayoutRecoveryJob recoveryJob;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private TransactionTemplate tx;

    private long freelancerId;
    private long walletId;

    @BeforeEach
    void setUp() {
        freelancerId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
        walletId = accountRepository.create("FREELANCER_WALLET:" + freelancerId, AccountType.FREELANCER_WALLET,
            freelancerId, false).id();
        long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();
        tx.executeWithoutResult(s -> ledgerService.post(new PostingRequest("TEST_SEED", "TEST_SEED", "payout-it",
            "payout-seed:" + freelancerId, "seed", List.of(new Leg(bank, -500_000), new Leg(walletId, 500_000)))));
    }

    @Test
    @DisplayName("SUCCESS → CONFIRMED; wallet down, EXTERNAL_BANK:PAYOUTS up")
    void successConfirms() {
        PROVIDER.setMode(Mode.SUCCESS);
        long bankBefore = balance("EXTERNAL_BANK:PAYOUTS");

        Payout p = request();

        assertThat(p.state()).isEqualTo(PayoutState.CONFIRMED);
        assertThat(wallet()).isEqualTo(500_000 - AMOUNT);
        assertThat(balance("EXTERNAL_BANK:PAYOUTS") - bankBefore).isEqualTo(AMOUNT);
    }

    @Test
    @DisplayName("FAIL → FAILED; wallet restored")
    void failReverses() {
        PROVIDER.setMode(Mode.FAIL);
        Payout p = request();
        assertThat(p.state()).isEqualTo(PayoutState.FAILED);
        assertThat(wallet()).isEqualTo(500_000);
    }

    @Test
    @DisplayName("TIMEOUT_THEN_SUCCEED → UNKNOWN; recovery → CONFIRMED; one provider record, no reverse entry")
    void timeoutThenSucceedIsRecoveredWithoutRetrying() {
        PROVIDER.setMode(Mode.TIMEOUT_THEN_SUCCEED, TIMEOUT_DELAY_MS, null, 1L);
        Payout p = request();
        assertThat(p.state()).isEqualTo(PayoutState.UNKNOWN);

        clock.advance(Duration.ofMinutes(1));
        recoveryJob.runOnce();

        assertThat(state(p)).isEqualTo(PayoutState.CONFIRMED);
        assertThat(PROVIDER.postCount(p.id().toString())).as("never POSTed again").isEqualTo(1);
        assertThat(PROVIDER.byKey(p.id().toString())).hasValueSatisfying(r -> assertThat(r.status()).isEqualTo("SUCCESS"));
        assertThat(entries(p, "PAYOUT_REVERSE") + entries(p, "PAYOUT_CANCEL")).isZero();
        assertThat(wallet()).isEqualTo(500_000 - AMOUNT);
    }

    @Test
    @DisplayName("TIMEOUT_THEN_FAIL → UNKNOWN → FAILED; wallet restored")
    void timeoutThenFailReverses() {
        PROVIDER.setMode(Mode.TIMEOUT_THEN_FAIL, TIMEOUT_DELAY_MS, null, 1L);
        Payout p = request();
        assertThat(p.state()).isEqualTo(PayoutState.UNKNOWN);

        clock.advance(Duration.ofMinutes(1));
        recoveryJob.runOnce();

        assertThat(state(p)).isEqualTo(PayoutState.FAILED);
        assertThat(wallet()).isEqualTo(500_000);
    }

    @Test
    @DisplayName("TIMEOUT_NOT_RECEIVED → UNKNOWN → after 2 min the key is tombstoned → CANCELLED; a late POST gets 409")
    void lostRequestIsTombstonedAndALateRequestIsRejected() throws Exception {
        PROVIDER.setMode(Mode.TIMEOUT_NOT_RECEIVED, TIMEOUT_DELAY_MS, null, 1L);
        Payout p = request();
        assertThat(p.state()).isEqualTo(PayoutState.UNKNOWN);

        clock.advance(Duration.ofMinutes(3));
        recoveryJob.runOnce();

        assertThat(state(p)).isEqualTo(PayoutState.CANCELLED);
        assertThat(wallet()).isEqualTo(500_000);

        // The original request finally arrives: the tombstone makes it harmless.
        PROVIDER.setMode(Mode.SUCCESS);
        HttpResponse<String> late = PROVIDER.postPayout(p.id().toString(), "late", AMOUNT);
        assertThat(late.statusCode()).isEqualTo(409);
        assertThat(PROVIDER.byKey(p.id().toString())).hasValueSatisfying(r -> assertThat(r.status()).isEqualTo("CANCELLED"));
    }

    @Test
    @DisplayName("ERROR_500 → UNKNOWN → status 404 → tombstone → CANCELLED")
    void error500EndsCancelled() {
        PROVIDER.setMode(Mode.ERROR_500);
        Payout p = request();
        assertThat(p.state()).isEqualTo(PayoutState.UNKNOWN);

        clock.advance(Duration.ofMinutes(3));
        recoveryJob.runOnce();

        assertThat(state(p)).isEqualTo(PayoutState.CANCELLED);
        assertThat(wallet()).isEqualTo(500_000);
    }

    @Test
    @DisplayName("a payout stuck in RESERVED (crash before the provider call) is resolved by the job")
    void stuckReservedIsResolved() {
        PROVIDER.setMode(Mode.SUCCESS);
        UUID id = UUID.randomUUID();
        long inFlight = accountRepository.findByCode("PAYOUT_IN_FLIGHT").orElseThrow().id();
        // T1 only, exactly as PayoutService commits it, and then "the process dies".
        tx.executeWithoutResult(s -> {
            ledgerService.post(new PostingRequest("PAYOUT_RESERVE", "PAYOUT", id.toString(), "payout:reserve:" + id,
                "reserve", List.of(new Leg(walletId, -AMOUNT), new Leg(inFlight, AMOUNT))));
            payoutRepository.createReserved(id, freelancerId, walletId, AMOUNT);
        });

        clock.advance(Duration.ofMinutes(3));
        recoveryJob.runOnce();

        assertThat(payoutRepository.findById(id).orElseThrow().state()).isEqualTo(PayoutState.CANCELLED);
        assertThat(wallet()).isEqualTo(500_000);
    }

    @Test
    @DisplayName("two recovery jobs in parallel over 100 UNKNOWN payouts → each settles exactly once")
    void parallelRecoverySettlesEachPayoutOnce() throws Exception {
        PROVIDER.setMode(Mode.ERROR_500);
        List<Payout> payouts = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            payouts.add(request());
        }
        assertThat(payouts).allSatisfy(p -> assertThat(p.state()).isEqualTo(PayoutState.UNKNOWN));
        // The provider did pay them after all: the same keys arrive again and are paid.
        PROVIDER.setMode(Mode.SUCCESS);
        payouts.forEach(p -> assertThat(PROVIDER.postPayout(p.id().toString(), "late", AMOUNT).statusCode()).isEqualTo(200));
        long bankBefore = balance("EXTERNAL_BANK:PAYOUTS");

        for (int round = 0; round < 5 && payouts.stream().anyMatch(p -> !state(p).isTerminal()); round++) {
            clock.advance(Duration.ofMinutes(2));
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                pool.submit(recoveryJob::runOnce);
                pool.submit(recoveryJob::runOnce);
                pool.shutdown();
                assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
            }
        }

        assertThat(payouts).allSatisfy(p -> assertThat(state(p)).isEqualTo(PayoutState.CONFIRMED));
        assertThat(payouts).allSatisfy(p -> assertThat(entries(p, "PAYOUT_CONFIRM")).isEqualTo(1));
        assertThat(balance("EXTERNAL_BANK:PAYOUTS") - bankBefore).isEqualTo(100 * AMOUNT);
    }

    @Test
    @DisplayName("ASYNC → PROCESSING; the provider settles SUCCESS seconds later; recovery → CONFIRMED")
    void asyncSuccessIsConfirmedByRecovery() {
        PROVIDER.setMode(Mode.ASYNC, null, null, ASYNC_SUCCESS_SEED);
        long bankBefore = balance("EXTERNAL_BANK:PAYOUTS");
        Payout p = request();
        assertThat(p.state()).isEqualTo(PayoutState.PROCESSING);
        assertThat(wallet()).as("reserved while processing").isEqualTo(500_000 - AMOUNT);

        recoverUntilTerminal(p);

        assertThat(state(p)).isEqualTo(PayoutState.CONFIRMED);
        assertThat(PROVIDER.byKey(p.id().toString())).hasValueSatisfying(r -> assertThat(r.status()).isEqualTo("SUCCESS"));
        assertThat(entries(p, "PAYOUT_CONFIRM")).isEqualTo(1);
        assertThat(balance("EXTERNAL_BANK:PAYOUTS") - bankBefore).isEqualTo(AMOUNT);
        assertThat(PROVIDER.postCount(p.id().toString())).as("never POSTed again").isEqualTo(1);
    }

    @Test
    @DisplayName("ASYNC → PROCESSING; the provider settles FAILED (the 10%); recovery → FAILED, wallet restored")
    void asyncFailureIsReversedByRecovery() {
        PROVIDER.setMode(Mode.ASYNC, null, null, ASYNC_FAILED_SEED);
        Payout p = request();
        assertThat(p.state()).isEqualTo(PayoutState.PROCESSING);

        recoverUntilTerminal(p);

        assertThat(state(p)).isEqualTo(PayoutState.FAILED);
        assertThat(PROVIDER.byKey(p.id().toString())).hasValueSatisfying(r -> assertThat(r.status()).isEqualTo("FAILED"));
        assertThat(entries(p, "PAYOUT_REVERSE")).isEqualTo(1);
        assertThat(wallet()).isEqualTo(500_000);
    }

    // --- helpers -----------------------------------------------------------------------------

    /**
     * The provider settles ASYNC payouts on its own (wall) clock, 5-15s after accepting them. Keep
     * running the recovery job, moving escrow's clock past each backoff, until the payout is done.
     */
    private void recoverUntilTerminal(Payout p) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            clock.advance(Duration.ofMinutes(11));   // past the longest backoff (600s)
            recoveryJob.runOnce();
            assertThat(state(p).isTerminal()).as("payout " + p.id() + " is " + state(p)).isTrue();
        });
    }

    private Payout request() {
        return payoutService.requestPayout(Actors.freelancer(freelancerId), freelancerId, walletId, AMOUNT, "saga-" + UUID.randomUUID());
    }

    private PayoutState state(Payout p) {
        return payoutRepository.findById(p.id()).orElseThrow().state();
    }

    private long entries(Payout p, String type) {
        return jdbcClient.sql("SELECT COUNT(*) FROM journal_entries WHERE reference_type = 'PAYOUT' AND reference_id = ? AND type = ?")
            .param(p.id().toString()).param(type).query(Long.class).single();
    }

    private long wallet() {
        return accountRepository.findById(walletId).orElseThrow().balance();
    }

    private long balance(String code) {
        return accountRepository.findByCode(code).orElseThrow().balance();
    }
}
