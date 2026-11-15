package com.svatsa.escrow.chaos;

import com.svatsa.escrow.test.ProviderControl;
import com.svatsa.escrow.test.ProviderControl.Mode;
import java.time.LocalDate;
import java.time.ZoneId;
import static org.awaitility.Awaitility.await;

import com.svatsa.escrow.test.Actors;
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
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C2: provider connection resets.
 *
 * 50 payouts against the real provider in RANDOM mode while a monkey thread keeps flapping two
 * reset_peer toxics on the provider proxy. The provider's weights: TIMEOUT_THEN_SUCCEED 40,
 * TIMEOUT_THEN_FAIL 20 (with delayMs 150: the bank pays or refuses, then answers 150ms later),
 * ASYNC 20 (202 PROCESSING, settled 5-15s later) and ERROR_500 20 (nothing recorded). The 150ms
 * late answer is what gives the downstream toxic a window to lose a response after the bank acted.
 * <ul>
 *   <li>upstream, timeout 0: the connection dies before the request reaches the provider
 *       (provider never pays, client sees an IO error)</li>
 *   <li>downstream, timeout 50ms: the provider records the payout (it answers 150ms later)
 *       and the response is lost (provider PAID, client sees an IO error)</li>
 * </ul>
 * The client can't tell those two apart, so both must land in UNKNOWN, and only the recovery
 * job's status check / tombstone may decide. The toxics stay on for the first recovery passes.
 *
 * Pass criteria: every payout terminal; ledger matches provider 1:1 (CONFIRMED ⇔ SUCCESS,
 * FAILED ⇔ FAILED, CANCELLED ⇔ tombstone); no key POSTed more than once; money adds up;
 * InvariantChecker clean.
 */
@DisplayName("C2: provider connection reset")
class C2ProviderConnectionResetChaosIT extends AbstractChaosIT {

    private static final Logger log = LoggerFactory.getLogger(C2ProviderConnectionResetChaosIT.class);

    private static final int PAYOUTS = 50;
    private static final int WORKERS = 5;
    private static final int RECOVERY_PASSES_UNDER_FAULT = 5;
    private static final long FUNDED_PAISE = 10_000_000L;

    @Autowired private PayoutService payoutService;
    @Autowired private PayoutRepository payoutRepository;
    @Autowired private PayoutRecoveryJob recoveryJob;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private TransactionTemplate tx;

    @Test
    @DisplayName("every payout settles exactly as the provider says, despite connection resets")
    void connectionResetsNeverDesyncLedgerFromProvider() throws Exception {
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        PROVIDER.setMode(Mode.RANDOM, 150L, Map.of(
            Mode.TIMEOUT_THEN_SUCCEED, 40, Mode.TIMEOUT_THEN_FAIL, 20, Mode.ASYNC, 20, Mode.ERROR_500, 20), 2002L);

        long freelancerId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
        long walletId = accountRepository.create("FREELANCER_WALLET:" + freelancerId,
            AccountType.FREELANCER_WALLET, freelancerId, false).id();
        fund(walletId, FUNDED_PAISE);
        long bankBefore = balance("EXTERNAL_BANK:PAYOUTS");

        // --- Fault on, fire payouts ---------------------------------------------------------
        AtomicBoolean monkeyRunning = new AtomicBoolean(true);
        Thread monkey = Thread.ofPlatform().name("c2-monkey").start(() -> flapResets(monkeyRunning));

        Random amounts = new Random(7);
        List<Long> amountList = new ArrayList<>();
        for (int i = 0; i < PAYOUTS; i++) {
            amountList.add(100L + amounts.nextInt(4_900));
        }
        List<UUID> payoutIds = Collections.synchronizedList(new ArrayList<>());
        List<String> requestErrors = Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newFixedThreadPool(WORKERS)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < PAYOUTS; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    try {
                        Payout p = payoutService.requestPayout(Actors.freelancer(freelancerId), freelancerId, walletId, amountList.get(n), "c2-" + n);
                        payoutIds.add(p.id());
                    } catch (RuntimeException e) {
                        requestErrors.add("payout " + n + ": " + e);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(2, TimeUnit.MINUTES);
            }
        }

        Map<PayoutState, Integer> afterRequests = countStates(payoutIds);
        log.info("c2.after-requests states={}", afterRequests);

        // --- Recovery: first passes still under fault, then healed ---------------------------
        int[] passes = {0};
        while (passes[0] < RECOVERY_PASSES_UNDER_FAULT && !allTerminal(payoutIds)) {
            clock.advance(Duration.ofMinutes(15));
            recoveryJob.runOnce();
            passes[0]++;
        }
        monkeyRunning.set(false);
        monkey.join();
        removeAllToxics(PROVIDER_PROXY);
        // ASYNC payouts settle on the provider's wall clock (5-15s), so recovery needs real time
        // too. A pass every 2s keeps each payout well under the job's 20-attempt limit.
        try {
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2)).until(() -> {
                clock.advance(Duration.ofMinutes(15));
                recoveryJob.runOnce();
                passes[0]++;
                return allTerminal(payoutIds);
            });
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            log.warn("c2.recovery-timeout passes={}", passes[0]);   // reported below as "not terminal"
        }

        // --- Verify ---------------------------------------------------------------------------
        List<String> failures = new ArrayList<>(requestErrors);
        if (payoutIds.size() != PAYOUTS) {
            failures.add("expected " + PAYOUTS + " payouts, created " + payoutIds.size());
        }

        // What the bank paid: its settlements, plus a by-key lookup for every payout we created. The
        // provider runs on the test clock, which recovery moved forward (15 min a pass), so take the
        // IST day from that clock and include the days either side: a run near midnight IST settles
        // payouts on the next day.
        LocalDate todayIst = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Kolkata"));
        List<ProviderControl.Record> settled = new ArrayList<>();
        for (int d = -1; d <= 1; d++) {
            settled.addAll(PROVIDER.settlements(todayIst.plusDays(d)));
        }
        Map<PayoutState, Integer> finalStates = new EnumMap<>(PayoutState.class);
        long confirmedPaise = 0;
        int faultedCalls = 0;
        for (UUID id : payoutIds) {
            Payout p = payoutRepository.findById(id).orElseThrow();
            finalStates.merge(p.state(), 1, Integer::sum);
            ProviderControl.Record rec = PROVIDER.byKey(id.toString()).orElse(null);
            String providerStatus = rec == null ? "NONE" : rec.status();

            String expectedProvider = switch (p.state()) {
                case CONFIRMED -> "SUCCESS";
                case FAILED -> "FAILED";
                case CANCELLED -> "CANCELLED";
                default -> null;
            };
            if (expectedProvider == null) {
                failures.add(id + " not terminal: " + p.state());
            } else if (!expectedProvider.equals(providerStatus)) {
                failures.add(id + " ledger=" + p.state() + " provider=" + providerStatus);
            }
            if (p.state() == PayoutState.CONFIRMED) {
                confirmedPaise += p.amountPaise();
            }
            int posts = PROVIDER.postCount(id.toString());
            if (posts > 1) {
                failures.add(id + " was POSTed to the provider " + posts + " times (blind retry)");
            }
            if (posts == 0 || rec == null || "CANCELLED".equals(providerStatus)) {
                faultedCalls++;
            }
        }
        for (ProviderControl.Record r : settled) {
            if (payoutIds.stream().noneMatch(id -> id.toString().equals(r.idempotencyKey()))) {
                failures.add("provider settled a payout for unknown key " + r.idempotencyKey());
            }
        }

        long providerPaid = settled.stream().mapToLong(ProviderControl.Record::amountPaise).sum();
        long bankDelta = balance("EXTERNAL_BANK:PAYOUTS") - bankBefore;
        long walletNow = accountRepository.findById(walletId).orElseThrow().balance();
        if (providerPaid != confirmedPaise) {
            failures.add("provider paid " + providerPaid + " but ledger confirmed " + confirmedPaise);
        }
        if (bankDelta != confirmedPaise) {
            failures.add("EXTERNAL_BANK:PAYOUTS moved " + bankDelta + ", confirmed " + confirmedPaise);
        }
        if (walletNow != FUNDED_PAISE - confirmedPaise) {
            failures.add("wallet " + walletNow + " != funded - confirmed " + (FUNDED_PAISE - confirmedPaise));
        }

        List<String> violations = new ArrayList<>(failures);
        violations.addAll(describe(invariantChecker.check()));

        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("statesAfterRequests", afterRequests);
        notes.put("finalStates", finalStates);
        notes.put("recoveryPasses", passes[0]);
        notes.put("providerNeverPaidOrTombstoned", faultedCalls);
        notes.put("providerPaidPaise", providerPaid);
        notes.put("ledgerConfirmedPaise", confirmedPaise);
        // Reconcile the scenario's day with both sources, faults off: 0 mismatches is part of passing.
        removeAllToxics(PROVIDER_PROXY);
        List<String> mismatches = reconcileToday();
        var file = ChaosResults.write("C2-provider-connection-reset", startedAt,
            Duration.ofNanos(System.nanoTime() - startNanos).toMillis(), PAYOUTS + passes[0], violations, mismatches, notes);
        log.info("c2.result file={} violations={} notes={}", file, violations.size(), notes);

        assertThat(violations).as("C2 violations").isEmpty();
        assertThat(mismatches).as("C2 reconciliation mismatches").isEmpty();
        assertThat(afterRequests.getOrDefault(PayoutState.UNKNOWN, 0))
            .as("the faults must actually have produced UNKNOWN payouts, or this scenario proved nothing")
            .isPositive();
    }

    /** Keep re-adding the reset toxics so pooled connections get re-rolled, until told to stop. */
    private void flapResets(AtomicBoolean running) {
        Random random = new Random(99);
        while (running.get()) {
            try {
                PROVIDER_PROXY.toxics().resetPeer("reset-up", ToxicDirection.UPSTREAM, 0).setToxicity(0.25f);
                PROVIDER_PROXY.toxics().resetPeer("reset-down", ToxicDirection.DOWNSTREAM, 50).setToxicity(0.35f);
                Thread.sleep(200 + random.nextInt(400));
                removeAllToxics(PROVIDER_PROXY);
                Thread.sleep(100 + random.nextInt(200));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("c2.monkey error={}", e.toString());
            }
        }
    }

    private boolean allTerminal(List<UUID> ids) {
        return ids.stream().allMatch(id -> payoutRepository.findById(id).orElseThrow().isTerminal());
    }

    private Map<PayoutState, Integer> countStates(List<UUID> ids) {
        Map<PayoutState, Integer> counts = new EnumMap<>(PayoutState.class);
        for (UUID id : ids) {
            counts.merge(payoutRepository.findById(id).orElseThrow().state(), 1, Integer::sum);
        }
        return counts;
    }

    private void fund(long walletId, long amount) {
        long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();
        tx.executeWithoutResult(s -> ledgerService.post(new PostingRequest(
            "TEST_SEED", "TEST_SEED", String.valueOf(walletId), "c2-seed:" + UUID.randomUUID(), "C2 seed",
            List.of(new Leg(bank, -amount), new Leg(walletId, amount)))));
    }

    private long balance(String code) {
        return accountRepository.findByCode(code).orElseThrow().balance();
    }
}
