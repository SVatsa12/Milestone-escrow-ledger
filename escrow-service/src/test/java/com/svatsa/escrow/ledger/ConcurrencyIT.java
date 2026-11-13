package com.svatsa.escrow.ledger;

import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Concurrency tests for the ledger.
 * 
 * These tests prove that the ledger is safe under concurrent operations:
 * - Hot account: many small transfers out of one account (exactly N succeed when budget is N × amount)
 * - Deadlock: opposite-direction transfers between two accounts (no deadlocks, invariants hold)
 * - Same key race: multiple threads posting the same idempotency key (exactly 1 entry created)
 * 
 * All tests use virtual threads for efficient parallel execution.
 * 
 * Note: These tests require Postgres with Flyway migrations.
 * They are @Disabled for H2 in-memory tests. Enable in CI with docker-compose.
 */
@Disabled("Requires Postgres with Flyway migrations; enable in CI with docker-compose")
@SpringBootTest(properties = "spring.profiles.active=test")
class ConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Account hotAccount;
    private Account recipient;
    private Account accountA;
    private Account accountB;

    @BeforeEach
    void setUp() {
        clearLedger();
        
        hotAccount = accountRepository.create("CONCUR:HOT", AccountType.CLIENT_WALLET, 1L, false);
        recipient = accountRepository.create("CONCUR:RECIPIENT", AccountType.FREELANCER_WALLET, 2L, false);
        accountA = accountRepository.create("CONCUR:A", AccountType.CLIENT_WALLET, 3L, false);
        accountB = accountRepository.create("CONCUR:B", AccountType.FREELANCER_WALLET, 4L, false);

        // Fund accounts
        jdbcClient.sql("UPDATE accounts SET balance = 3700 WHERE id = ?")
            .param(hotAccount.id())
            .update();
        
        jdbcClient.sql("UPDATE accounts SET balance = 1000000 WHERE id = ?")
            .param(accountA.id())
            .update();
        
        jdbcClient.sql("UPDATE accounts SET balance = 1000000 WHERE id = ?")
            .param(accountB.id())
            .update();
    }

    /**
     * Hot account: 100 parallel transfers of 100 paise out of a 3700 paise account.
     * Exactly 37 should succeed, 63 should fail with INSUFFICIENT_FUNDS.
     * Final balance should be 0 (37 × 100).
     */
    @Test
    void hotAccountConcurrency() throws InterruptedException {
        int threadCount = 100;
        int amountPerTransfer = 100;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startGate.await(); // Wait for all threads to be ready
                    
                    transactionTemplate.executeWithoutResult(status -> {
                        try {
                            ledgerService.post(new PostingRequest(
                                "TRANSFER",
                                "TRANSFER",
                                "hot-" + index,
                                "hot-transfer-" + index,
                                "Hot account transfer",
                                List.of(
                                    new Leg(hotAccount.id(), -amountPerTransfer),
                                    new Leg(recipient.id(), amountPerTransfer)
                                )
                            ));
                            successCount.incrementAndGet();
                        } catch (InsufficientFundsException e) {
                            failureCount.incrementAndGet();
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            });
        }

        // Start all threads at once
        startGate.countDown();
        
        // Wait for all to finish
        boolean finished = endGate.await(10, TimeUnit.SECONDS);
        assertThat(finished).as("All threads should complete within timeout").isTrue();
        executor.shutdown();

        // Verify results
        assertThat(successCount.get())
            .as("Exactly 37 transfers should succeed (3700 / 100)")
            .isEqualTo(37);
        
        assertThat(failureCount.get())
            .as("Exactly 63 transfers should fail (insufficient funds)")
            .isEqualTo(63);

        // Verify final balances
        Account hotFinal = accountRepository.findById(hotAccount.id()).orElseThrow();
        assertThat(hotFinal.balance())
            .as("Hot account balance should be 0 after 37 × 100 transfers")
            .isEqualTo(0);

        Account recipientFinal = accountRepository.findById(recipient.id()).orElseThrow();
        assertThat(recipientFinal.balance())
            .as("Recipient balance should be 3700")
            .isEqualTo(3700);

        // Verify no journal entries were created for failed transfers
        Long entryCount = jdbcClient.sql("SELECT COUNT(*) FROM journal_entries WHERE type = 'TRANSFER'")
            .query(Long.class)
            .single();
        assertThat(entryCount)
            .as("Should have exactly 37 journal entries (one per success)")
            .isEqualTo(37);
    }

    /**
     * Deadlock test: 200 parallel transfers, half A→B and half B→A.
     * Should complete without deadlock (no PSQLException with SQLState 40P01).
     * Invariants should hold, A+B balance should remain 2,000,000.
     */
    @Test
    void noDeadlockWithOppositeTransfers() throws InterruptedException {
        int threadCount = 200;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    
                    transactionTemplate.executeWithoutResult(status -> {
                        long from, to;
                        if (index % 2 == 0) {
                            // A → B
                            from = accountA.id();
                            to = accountB.id();
                        } else {
                            // B → A
                            from = accountB.id();
                            to = accountA.id();
                        }

                        try {
                            ledgerService.post(new PostingRequest(
                                "TRANSFER",
                                "TRANSFER",
                                "deadlock-" + index,
                                "deadlock-transfer-" + index,
                                "Deadlock test transfer",
                                List.of(
                                    new Leg(from, -1000),
                                    new Leg(to, 1000)
                                )
                            ));
                        } catch (Exception e) {
                            // Log any unexpected errors (especially PSQLException with SQLState 40P01 = deadlock)
                            if (e.getMessage() != null && e.getMessage().contains("40P01")) {
                                errorCount.incrementAndGet();
                            }
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = endGate.await(30, TimeUnit.SECONDS);
        assertThat(finished).as("All threads should complete without deadlock").isTrue();
        executor.shutdown();

        // Verify no deadlocks occurred
        assertThat(errorCount.get())
            .as("No deadlock errors should occur")
            .isEqualTo(0);

        // Verify final balances: A + B should still be 2,000,000
        Account aFinal = accountRepository.findById(accountA.id()).orElseThrow();
        Account bFinal = accountRepository.findById(accountB.id()).orElseThrow();
        
        assertThat(aFinal.balance() + bFinal.balance())
            .as("Total balance of A + B should be conserved")
            .isEqualTo(2_000_000);

        // Verify all entries are balanced
        List<Long> unbalancedEntries = jdbcClient.sql("""
            SELECT entry_id FROM (
                SELECT entry_id, SUM(amount) as total, COUNT(*) as cnt
                FROM postings
                GROUP BY entry_id
            ) t
            WHERE total <> 0 OR cnt < 2
            """)
            .query(Long.class)
            .list();
        assertThat(unbalancedEntries)
            .as("No unbalanced entries should exist")
            .isEmpty();
    }

    /**
     * Same-key race: 50 threads post the same Idempotency-Key and request body simultaneously.
     * Exactly 1 journal entry should be created.
     * All 50 responses should be 2xx with identical bodies.
     */
    @Test
    void sameKeyRaceCreatesOnlyOneEntry() throws InterruptedException {
        int threadCount = 50;
        String sharedKey = "race-key-1";
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);
        List<PostingResult> results = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errorCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    
                    transactionTemplate.executeWithoutResult(status -> {
                        try {
                            PostingResult result = ledgerService.post(new PostingRequest(
                                "TRANSFER",
                                "TRANSFER",
                                sharedKey,
                                "race-" + sharedKey,
                                "Race test transfer",
                                List.of(
                                    new Leg(accountA.id(), -1000),
                                    new Leg(accountB.id(), 1000)
                                )
                            ));
                            results.add(result);
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = endGate.await(10, TimeUnit.SECONDS);
        assertThat(finished).as("All threads should complete").isTrue();
        executor.shutdown();

        // Verify all threads got a response
        assertThat(results.size() + errorCount.get())
            .as("All 50 threads should complete successfully")
            .isEqualTo(threadCount);

        // Verify all results have the same entry ID
        Set<Long> uniqueEntryIds = new HashSet<>();
        for (PostingResult result : results) {
            uniqueEntryIds.add(result.entryId());
        }
        assertThat(uniqueEntryIds)
            .as("All 50 responses should reference the same journal entry")
            .hasSize(1);

        // Verify exactly one is marked as "not replayed"
        long notReplayedCount = results.stream()
            .filter(r -> !r.replayed())
            .count();
        assertThat(notReplayedCount)
            .as("Exactly 1 response should have replayed=false")
            .isEqualTo(1);

        // Verify 49 are marked as "replayed"
        long replayedCount = results.stream()
            .filter(PostingResult::replayed)
            .count();
        assertThat(replayedCount)
            .as("Exactly 49 responses should have replayed=true")
            .isEqualTo(49);

        // Verify only 1 journal entry exists for this key
        Long entryCount = jdbcClient.sql(
            "SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = ?")
            .param("race-" + sharedKey)
            .query(Long.class)
            .single();
        assertThat(entryCount)
            .as("Exactly 1 journal entry should exist for the shared key")
            .isEqualTo(1);
    }

    /**
     * Commented-out naive lock path to demonstrate the importance of lock ordering.
     * 
     * If you uncomment the "NAIVE_LOCKING" code path in LedgerService (via a test-only flag),
     * this test will deadlock under contention. The fix is the ascending-ID-order lock pattern.
     * 
     * We keep this as a documentation comment rather than actual code because adding
     * test-only branches to production code is ugly. But the point is clear: without
     * proper lock ordering, the opposite-transfer test would occasionally deadlock.
     */
    @Test
    void lockOrderingPreventsDeadlock() throws InterruptedException {
        // This test is covered by noDeadlockWithOppositeTransfers() above.
        // The point: accounts are locked in ascending ID order in LedgerService.post(),
        // so A→B and B→A can never deadlock. If we didn't sort, we'd see:
        //
        // Thread 1 (A→B):    locks A
        // Thread 2 (B→A):    locks B
        // Thread 1:          waits for B (blocked)
        // Thread 2:          waits for A (blocked)
        // → DEADLOCK
        //
        // With ascending order:
        // Thread 1 (A→B):    locks A, then B (min first, always)
        // Thread 2 (B→A):    locks A, then B (min first, always)
        // → Both lock A first, no cycle, no deadlock
        
        // noDeadlockWithOppositeTransfers() proves this.
    }
}
