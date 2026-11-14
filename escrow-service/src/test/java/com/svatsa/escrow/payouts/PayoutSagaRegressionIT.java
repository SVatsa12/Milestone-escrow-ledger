package com.svatsa.escrow.payouts;

import com.svatsa.escrow.test.Actors;
import com.svatsa.escrow.chaos.AbstractToxiproxyIT;
import com.svatsa.escrow.chaos.FakePayoutProvider;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.AccountType;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.PostingRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests (default build, not tagged "chaos") for payout-saga bugs that chaos
 * scenario C2 found. See chaos/FINDINGS.md.
 */
@DisplayName("Payout saga regressions found by chaos")
class PayoutSagaRegressionIT extends AbstractToxiproxyIT {

    @Autowired private PayoutService payoutService;
    @Autowired private PayoutRepository payoutRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private TransactionTemplate tx;

    @Test
    @DisplayName("T1 is committed before the provider is called")
    void reservationIsDurableBeforeProviderCall() {
        PROVIDER.reset(FakePayoutProvider.Mode.SUCCESS, 1, 0);
        AtomicReference<String> stateSeenByProvider = new AtomicReference<>("NOT_VISIBLE");
        // Runs on the provider's thread, i.e. on a different DB connection than the saga.
        PROVIDER.onPost(key -> jdbcClient.sql("SELECT state FROM payouts WHERE id = ?::uuid")
            .param(key).query(String.class).optional().ifPresent(stateSeenByProvider::set));

        long[] wallet = fundedWallet(10_000);
        Payout payout = payoutService.requestPayout(Actors.freelancer(wallet[0]), wallet[0], wallet[1], 1_000, "reg-t1-" + UUID.randomUUID());

        assertThat(stateSeenByProvider.get())
            .as("a crash after the provider call must not be able to lose the reservation")
            .isEqualTo("RESERVED");
        assertThat(payoutRepository.findById(payout.id()).orElseThrow().state()).isEqualTo(PayoutState.CONFIRMED);
    }

    @Test
    @DisplayName("the provider receives X-Payout-Idempotency-Key = payout id, exactly once")
    void providerGetsIdempotencyKey() {
        PROVIDER.reset(FakePayoutProvider.Mode.SUCCESS, 1, 0);
        long[] wallet = fundedWallet(10_000);

        Payout payout = payoutService.requestPayout(Actors.freelancer(wallet[0]), wallet[0], wallet[1], 2_500, "reg-key-" + UUID.randomUUID());

        // The fake answers 400 without the header, which used to turn every payout into FAILED.
        assertThat(PROVIDER.records()).containsKey(payout.id().toString());
        assertThat(PROVIDER.postCount(payout.id().toString())).isEqualTo(1);
        assertThat(payout.state()).isEqualTo(PayoutState.CONFIRMED);
        assertThat(accountRepository.findById(wallet[1]).orElseThrow().balance()).isEqualTo(7_500);
    }

    @Test
    @DisplayName("concurrent payouts from one wallet do not deadlock")
    void concurrentPayoutsFromOneWalletDoNotDeadlock() throws Exception {
        PROVIDER.reset(FakePayoutProvider.Mode.SUCCESS, 1, 0);
        long[] wallet = fundedWallet(1_000_000);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    try {
                        payoutService.requestPayout(Actors.freelancer(wallet[0]), wallet[0], wallet[1], 100, "reg-dl-" + n + "-" + wallet[1]);
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(2, TimeUnit.MINUTES);
            }
        }

        assertThat(errors).as("payout FK lock taken before ordered account locks caused deadlocks").isEmpty();
        assertThat(accountRepository.findById(wallet[1]).orElseThrow().balance()).isEqualTo(1_000_000 - 40 * 100);
    }

    /** Returns {freelancerId, walletAccountId}. */
    private long[] fundedWallet(long amount) {
        long freelancerId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
        long walletId = accountRepository.create("FREELANCER_WALLET:" + freelancerId,
            AccountType.FREELANCER_WALLET, freelancerId, false).id();
        long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();
        tx.executeWithoutResult(s -> ledgerService.post(new PostingRequest(
            "DEPOSIT", "TEST_SEED", String.valueOf(walletId), "reg-seed:" + UUID.randomUUID(), "seed",
            List.of(new Leg(bank, -amount), new Leg(walletId, amount)))));
        return new long[]{freelancerId, walletId};
    }
}
