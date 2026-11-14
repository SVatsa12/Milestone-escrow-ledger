package com.svatsa.escrow.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import com.svatsa.escrow.test.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4 concurrency tests. Every test funds its own fresh accounts through the ledger (never by
 * writing accounts.balance) and counts only its own journal entries, so it holds on a shared
 * database and leaves the invariants intact.
 */
class ConcurrencyIT extends AbstractIntegrationTest {

    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private TransactionTemplate tx;

    private final ObjectMapper json = new ObjectMapper();
    private String run;
    private ApiClient api;

    @BeforeEach
    void setUp() {
        run = UUID.randomUUID().toString().substring(0, 8);
        api = new ApiClient(serverPort);
    }

    /** 3a. A wallet with 3,700 paise; 100 parallel 100-paise transfers: exactly 37 succeed. */
    @Test
    void hotAccountConcurrency() throws Exception {
        Account hot = funded("HOT", 3_700);
        Account recipient = funded("RECIPIENT", 0);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        runConcurrently(100, i -> {
            try {
                tx.executeWithoutResult(s -> ledgerService.post(transfer("hot-" + run + "-" + i, hot, recipient, 100)));
                succeeded.incrementAndGet();
            } catch (InsufficientFundsException e) {
                insufficient.incrementAndGet();
            } catch (Throwable t) {
                unexpected.add(t);
            }
        });

        assertThat(unexpected).isEmpty();
        assertThat(succeeded.get()).isEqualTo(37);
        assertThat(insufficient.get()).isEqualTo(63);
        assertThat(balance(hot)).isZero();
        assertThat(balance(recipient)).isEqualTo(3_700);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM journal_entries WHERE idempotency_key LIKE ?")
            .param("hot-" + run + "-%").query(Long.class).single())
            .as("one entry per successful transfer, none for rejected ones").isEqualTo(37);
    }

    /** 3b. 200 parallel transfers, half A→B and half B→A: zero failures of any kind. */
    @Test
    void noDeadlockWithOppositeTransfers() throws Exception {
        Account a = funded("A", 1_000_000);
        Account b = funded("B", 1_000_000);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        runConcurrently(200, i -> {
            try {
                Account from = i % 2 == 0 ? a : b;
                Account to = i % 2 == 0 ? b : a;
                tx.executeWithoutResult(s -> ledgerService.post(transfer("dl-" + run + "-" + i, from, to, 1_000)));
            } catch (Throwable t) {
                failures.add(t);   // a deadlock (SQLState 40P01) or anything else
            }
        });

        assertThat(failures).as("no deadlock and no other failure").isEmpty();
        assertThat(balance(a) + balance(b)).isEqualTo(2_000_000);
    }

    /**
     * 3c. 50 clients send the same Idempotency-Key and body to the API at once: exactly one
     * journal entry, every response 2xx with an identical body, 49 marked Idempotent-Replayed.
     * (P4 specifies the API here: the HTTP idempotency layer is what serialises same-key requests.)
     */
    @Test
    void sameKeyRaceThroughTheApiCreatesOneEntry() throws Exception {
        Account a = funded("RACE_A", 100_000);
        Account b = funded("RACE_B", 0);
        String key = "race-" + run;
        String body = "{\"fromAccountId\":" + a.id() + ",\"toAccountId\":" + b.id() + ",\"amountPaise\":1000,\"memo\":\"race\"}";

        List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            calls.add(api.http().sendAsync(api.postRequest("/api/v1/transfers", key, ApiClient.admin(), body).build(),
                HttpResponse.BodyHandlers.ofString()));
        }
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (var c : calls) {
            responses.add(c.get(60, TimeUnit.SECONDS));
        }

        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(201));
        assertThat(responses.stream().map(r -> {
            try {
                return json.readTree(r.body());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).distinct()).as("identical bodies").hasSize(1);
        assertThat(responses.stream().filter(r -> r.headers().firstValue("Idempotent-Replayed").isPresent()))
            .hasSize(49);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = ?")
            .param("transfer:" + key).query(Long.class).single()).isEqualTo(1);
        assertThat(balance(b)).isEqualTo(1_000);
    }

    /**
     * 4. Why LedgerService locks accounts in ascending id order: two transactions that lock the
     * same pair of rows in opposite orders deadlock (Postgres aborts one with 40P01); the same
     * two transactions in ascending order both complete. Raw SQL with a barrier, so the
     * interleaving is forced rather than left to chance; no test-only code in production.
     */
    @Test
    void lockOrderingIsWhatPreventsTheDeadlock() throws Exception {
        long a = funded("LOCK_A", 0).id();
        long b = funded("LOCK_B", 0).id();

        List<Throwable> naive = lockPairConcurrently(new long[]{a, b}, new long[]{b, a});
        assertThat(naive).as("opposite lock order must deadlock").hasSize(1);
        assertThat(naive.get(0)).hasMessageContaining("deadlock");

        List<Throwable> ordered = lockPairConcurrently(new long[]{Math.min(a, b), Math.max(a, b)},
            new long[]{Math.min(a, b), Math.max(a, b)});
        assertThat(ordered).as("ascending lock order must not deadlock").isEmpty();
    }

    // --- helpers -----------------------------------------------------------------------------

    /** Both transactions lock their first row, wait for each other, then lock their second row. */
    private List<Throwable> lockPairConcurrently(long[] first, long[] second) throws Exception {
        CyclicBarrier bothHoldFirstLock = new CyclicBarrier(2);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        long[][] orders = {first, second};
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
            for (long[] order : orders) {
                fs.add(pool.submit(() -> {
                    try {
                        tx.executeWithoutResult(s -> {
                            lockRow(order[0]);
                            await(bothHoldFirstLock);
                            lockRow(order[1]);
                        });
                    } catch (DataAccessException e) {
                        errors.add(e);
                    }
                }));
            }
            for (var f : fs) {
                f.get(30, TimeUnit.SECONDS);
            }
        }
        return errors;
    }

    private void lockRow(long id) {
        jdbcClient.sql("SELECT id FROM accounts WHERE id = ? FOR UPDATE").param(id).query(Long.class).single();
    }

    /**
     * Wait until both transactions hold their first lock. In the ascending-order case the second
     * transaction blocks on the first row and never arrives, so the barrier times out and the
     * first transaction simply carries on (and the second follows once the lock is released).
     */
    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(3, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.BrokenBarrierException e) {
            // expected when the other transaction is queued behind our lock
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runConcurrently(int n, java.util.function.IntConsumer task) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        task.accept(idx);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).as("all tasks finished").isTrue();
        }
    }

    /** A fresh wallet, funded through the ledger from EXTERNAL_BANK:RAZORPAY. */
    private Account funded(String name, long amount) {
        Account acc = accountRepository.create("CONCUR:" + name + ":" + run, AccountType.CLIENT_WALLET, null, false);
        if (amount > 0) {
            long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();
            tx.executeWithoutResult(s -> ledgerService.post(new PostingRequest("DEPOSIT", "TEST_SEED", acc.code(),
                "concur-seed:" + acc.code(), "seed", List.of(new Leg(bank, -amount), new Leg(acc.id(), amount)))));
        }
        return acc;
    }

    private PostingRequest transfer(String key, Account from, Account to, long amount) {
        return new PostingRequest("TRANSFER", "TRANSFER", key, key, "concurrency test",
            List.of(new Leg(from.id(), -amount), new Leg(to.id(), amount)));
    }

    private long balance(Account a) {
        return accountRepository.findById(a.id()).orElseThrow().balance();
    }
}
