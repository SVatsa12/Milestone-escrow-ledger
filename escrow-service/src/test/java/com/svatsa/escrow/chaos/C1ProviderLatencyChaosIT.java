package com.svatsa.escrow.chaos;

import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.AccountType;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.PostingRequest;
import com.svatsa.escrow.payouts.Payout;
import com.svatsa.escrow.payouts.PayoutRecoveryJob;
import com.svatsa.escrow.payouts.PayoutRepository;
import com.svatsa.escrow.payouts.PayoutService;
import com.svatsa.escrow.payouts.PayoutState;
import com.svatsa.escrow.test.Actors;
import com.svatsa.escrow.test.ProviderControl;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * C1: provider latency.
 *
 * <ul>
 *   <li>3000ms of downstream latency on the provider proxy, above escrow's 2s read timeout. The
 *       provider (SUCCESS mode) pays at once; escrow never hears back in time.</li>
 *   <li>100 payouts: every one must end UNKNOWN, never CONFIRMED or FAILED by guesswork, and never
 *       be retried (invariant 12).</li>
 *   <li>The latency goes away; the recovery job asks the provider and confirms every payout.</li>
 *   <li>Pass: all 100 CONFIRMED, no reverse entries, one POST per key, the provider settled exactly
 *       what the ledger confirmed, InvariantChecker clean (the @AfterEach checks it too).</li>
 * </ul>
 *
 * This used to be a @Disabled template that never touched Toxiproxy and paid out from account 1
 * for freelancer 42 (which the actor checks now reject). It runs for real now.
 */
@DisplayName("C1: provider latency")
class C1ProviderLatencyChaosIT extends AbstractChaosIT {

    private static final int PAYOUTS = 100;
    private static final int WORKERS = 20;
    private static final long AMOUNT = 10_000;

    @Autowired private PayoutService payoutService;
    @Autowired private PayoutRepository payoutRepository;
    @Autowired private PayoutRecoveryJob recoveryJob;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private TransactionTemplate tx;

    @Test
    @DisplayName("timeouts end UNKNOWN, recovery confirms every payout exactly once")
    void latencyThenRecovery() throws Exception {
        java.time.Instant startedAt = java.time.Instant.now();
        long startNanos = System.nanoTime();
        long freelancerId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
        long walletId = accountRepository.create("FREELANCER_WALLET:" + freelancerId,
            AccountType.FREELANCER_WALLET, freelancerId, false).id();
        fund(walletId, PAYOUTS * AMOUNT);
        long bankBefore = balance("EXTERNAL_BANK:PAYOUTS");

        // --- Latency on: every answer arrives after the client gave up -----------------------
        PROVIDER_PROXY.toxics().latency("c1-latency", ToxicDirection.DOWNSTREAM, 3_000);
        org.slf4j.LoggerFactory.getLogger(getClass()).info("c1.toxic-added proxy=provider toxics={}",
            PROVIDER_PROXY.toxics().getAll().stream().map(t -> t.getName() + ":" + t.getStream()).toList());
        List<UUID> ids = Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newFixedThreadPool(WORKERS)) {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < PAYOUTS; i++) {
                final int n = i;
                calls.add(pool.submit(() -> ids.add(payoutService.requestPayout(
                    Actors.freelancer(freelancerId), freelancerId, walletId, AMOUNT, "c1-" + n).id())));
            }
            for (Future<?> f : calls) {
                f.get(2, TimeUnit.MINUTES);
            }
        }
        assertThat(ids).hasSize(PAYOUTS);
        assertThat(ids).as("a timeout is UNKNOWN, never a guess").allSatisfy(id -> assertThat(state(id)).isEqualTo(PayoutState.UNKNOWN));
        assertThat(balance("EXTERNAL_BANK:PAYOUTS")).as("nothing confirmed yet").isEqualTo(bankBefore);
        // The bank did pay every one of them.
        assertThat(ids).allSatisfy(id -> assertThat(PROVIDER.byKey(id.toString()))
            .hasValueSatisfying(r -> assertThat(r.status()).isEqualTo("SUCCESS")));

        // --- Latency off: recovery asks, never re-POSTs -----------------------------------------
        removeAllToxics(PROVIDER_PROXY);
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
            clock.advance(Duration.ofMinutes(1));
            recoveryJob.runOnce();
            return ids.stream().allMatch(id -> state(id).isTerminal());
        });

        assertThat(ids).allSatisfy(id -> assertThat(state(id)).isEqualTo(PayoutState.CONFIRMED));
        assertThat(ids).allSatisfy(id -> assertThat(PROVIDER.postCount(id.toString())).as("POSTs for " + id).isEqualTo(1));
        assertThat(jdbcClient.sql("SELECT count(*) FROM journal_entries WHERE reference_type = 'PAYOUT' AND reference_id IN ("
                + String.join(",", ids.stream().map(id -> "'" + id + "'").toList())
                + ") AND type IN ('PAYOUT_REVERSE', 'PAYOUT_CANCEL')")
            .query(Long.class).single()).as("no reverse entries").isZero();

        // The provider runs on the (moved) test clock: take the IST day from it, with the days either side.
        LocalDate todayIst = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Kolkata"));
        long providerSettled = 0;
        for (int d = -1; d <= 1; d++) {
            providerSettled += PROVIDER.settlements(todayIst.plusDays(d)).stream().mapToLong(ProviderControl.Record::amountPaise).sum();
        }
        assertThat(providerSettled).as("provider settled").isEqualTo(PAYOUTS * AMOUNT);
        assertThat(balance("EXTERNAL_BANK:PAYOUTS") - bankBefore).as("ledger confirmed").isEqualTo(PAYOUTS * AMOUNT);
        assertThat(accountRepository.findById(walletId).orElseThrow().balance()).isZero();

        List<String> violations = describe(invariantChecker.check());
        List<String> mismatches = reconcileToday();
        java.util.Map<String, Object> notes = new java.util.LinkedHashMap<>();
        notes.put("payouts", PAYOUTS);
        notes.put("latencyToxicMs", 3_000);
        notes.put("providerSettledPaise", providerSettled);
        ChaosResults.write("C1-provider-latency", startedAt, Duration.ofNanos(System.nanoTime() - startNanos).toMillis(),
            PAYOUTS, violations, mismatches, notes);
        assertThat(violations).as("C1 invariants").isEmpty();
        assertThat(mismatches).as("C1 reconciliation mismatches").isEmpty();
    }

    private PayoutState state(UUID id) {
        return payoutRepository.findById(id).map(Payout::state).orElseThrow();
    }

    private void fund(long walletId, long amount) {
        long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();
        tx.executeWithoutResult(s -> ledgerService.post(new PostingRequest(
            "TEST_SEED", "TEST_SEED", String.valueOf(walletId), "c1-seed:" + UUID.randomUUID(), "C1 seed",
            List.of(new Leg(bank, -amount), new Leg(walletId, amount)))));
    }

    private long balance(String code) {
        return accountRepository.findByCode(code).orElseThrow().balance();
    }
}
