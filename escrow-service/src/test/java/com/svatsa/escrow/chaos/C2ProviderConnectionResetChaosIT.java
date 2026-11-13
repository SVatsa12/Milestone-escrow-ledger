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
 * 50 payouts against a provider in RANDOM mode (SUCCESS / FAILED / PROCESSING / 500) while a
 * monkey thread keeps flapping two reset_peer toxics on the provider proxy:
 * <ul>
 *   <li>upstream, timeout 0: the connection dies before the request reaches the provider
 *       (provider never pays, client sees an IO error)</li>
 *   <li>downstream, timeout 50ms: the provider records the payout (it takes 150ms to answer)
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
    private static final int MAX_RECOVERY_PASSES = 40;
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
        PROVIDER.reset(FakePayoutProvider.Mode.RANDOM, 2002L, 150);

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
                        Payout p = payoutService.requestPayout(freelancerId, walletId, amountList.get(n), "c2-" + n);
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
        int passes = 0;
        while (passes < MAX_RECOVERY_PASSES && !allTerminal(payoutIds)) {
            if (passes == RECOVERY_PASSES_UNDER_FAULT) {
                monkeyRunning.set(false);
                monkey.join();
                removeAllToxics(PROVIDER_PROXY);
            }
            clock.advance(Duration.ofMinutes(15));
            recoveryJob.runOnce();
            passes++;
        }
        monkeyRunning.set(false);
        monkey.join();
        removeAllToxics(PROVIDER_PROXY);

        // --- Verify ---------------------------------------------------------------------------
        List<String> failures = new ArrayList<>(requestErrors);
        if (payoutIds.size() != PAYOUTS) {
            failures.add("expected " + PAYOUTS + " payouts, created " + payoutIds.size());
        }

        Map<String, FakePayoutProvider.ProviderRecord> provider = PROVIDER.records();
        Map<PayoutState, Integer> finalStates = new EnumMap<>(PayoutState.class);
        long confirmedPaise = 0;
        int faultedCalls = 0;
        for (UUID id : payoutIds) {
            Payout p = payoutRepository.findById(id).orElseThrow();
            finalStates.merge(p.state(), 1, Integer::sum);
            FakePayoutProvider.ProviderRecord rec = provider.get(id.toString());
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
        for (String key : provider.keySet()) {
            if (payoutIds.stream().noneMatch(id -> id.toString().equals(key))) {
                failures.add("provider has a record for unknown key " + key);
            }
        }

        long providerPaid = provider.values().stream()
            .filter(r -> "SUCCESS".equals(r.status())).mapToLong(FakePayoutProvider.ProviderRecord::amountPaise).sum();
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
        notes.put("recoveryPasses", passes);
        notes.put("providerNeverPaidOrTombstoned", faultedCalls);
        notes.put("providerPaidPaise", providerPaid);
        notes.put("ledgerConfirmedPaise", confirmedPaise);
        var file = ChaosResults.write("C2-provider-connection-reset", startedAt,
            Duration.ofNanos(System.nanoTime() - startNanos).toMillis(), PAYOUTS + passes, violations, notes);
        log.info("c2.result file={} violations={} notes={}", file, violations.size(), notes);

        assertThat(violations).as("C2 violations").isEmpty();
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
            "DEPOSIT", "TEST_SEED", String.valueOf(walletId), "c2-seed:" + UUID.randomUUID(), "C2 seed",
            List.of(new Leg(bank, -amount), new Leg(walletId, amount)))));
    }

    private long balance(String code) {
        return accountRepository.findByCode(code).orElseThrow().balance();
    }
}
