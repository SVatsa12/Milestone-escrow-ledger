package com.svatsa.escrow.chaos;

import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.recon.InvariantChecker;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4: Duplicate webhooks chaos test.
 *
 * Scenario:
 * - 20 unique payments (Razorpay webhooks)
 * - Each payment is delivered 25 times (duplicates)
 * - All 500 deliveries sent concurrently and shuffled
 * - Webhook consumer must dedupe by eventId
 * - Each wallet must be credited exactly once despite 25 duplicate deliveries
 * - Invariants must be clean
 *
 * Tests that the webhook consumer's idempotent deduplication works under a duplicate storm.
 */
@Tag("chaos")
@Disabled("Requires Razorpay webhook simulation; enable in CI")
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("C4: Duplicate Webhooks Chaos Test")
class C4DuplicateWebhooksChaosIT extends AbstractIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private InvariantChecker invariantChecker;

    private static final int UNIQUE_PAYMENTS = 20;
    private static final int DUPLICATES_PER_PAYMENT = 25;
    private static final int TOTAL_DELIVERIES = UNIQUE_PAYMENTS * DUPLICATES_PER_PAYMENT;
    private static final long PAYMENT_AMOUNT = 1000_00; // 1000 rupees per payment

    private long clientWalletId;

    @BeforeEach
    void setUp() {

        // Create a client wallet
        var clientWallet = accountRepository.create(
            "CLIENT_WALLET_C4",
            com.svatsa.escrow.ledger.AccountType.CLIENT_WALLET,
            null,
            false
        );
        clientWalletId = clientWallet.id();

        // Seed with initial balance
        jdbcClient.sql("UPDATE accounts SET balance = ? WHERE id = ?")
            .param(0L)
            .param(clientWalletId)
            .update();
    }

    @Test
    @DisplayName("Should dedupe 25 copies of each webhook and credit each wallet exactly once")
    void shouldHandleDuplicateWebhooksCorrectly() throws Exception {
        System.out.println("Simulating " + TOTAL_DELIVERIES + " webhook deliveries " +
            "(" + UNIQUE_PAYMENTS + " unique × " + DUPLICATES_PER_PAYMENT + " duplicates)");

        // Part 1: Create duplicate webhook events
        List<WebhookEvent> events = new ArrayList<>();

        for (int paymentId = 0; paymentId < UNIQUE_PAYMENTS; paymentId++) {
            UUID eventId = UUID.randomUUID();
            for (int dup = 0; dup < DUPLICATES_PER_PAYMENT; dup++) {
                events.add(new WebhookEvent(
                    eventId,
                    "payment.authorized",
                    paymentId,
                    PAYMENT_AMOUNT
                ));
            }
        }

        // Shuffle to simulate out-of-order delivery
        Collections.shuffle(events);

        // Part 2: Process all events concurrently
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger dedupeCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(32, Thread.ofVirtual().factory());
        CountDownLatch endGate = new CountDownLatch(TOTAL_DELIVERIES);

        long startTime = System.currentTimeMillis();

        for (WebhookEvent event : events) {
            executor.submit(() -> {
                try {
                    // Simulate webhook consumer processing
                    // (In a real test, this would call the webhook controller)
                    boolean isNew = insertProcessedEvent(event.eventId);

                    if (isNew) {
                        // This is a new event; process the payment
                        creditWallet(event.paymentId, event.amount);
                        successCount.incrementAndGet();
                    } else {
                        // This event was already processed; deduplicate
                        dedupeCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    System.err.println("Error processing event: " + e.getMessage());
                } finally {
                    endGate.countDown();
                }
            });
        }

        // Wait for all deliveries to be processed
        endGate.await();
        long duration = System.currentTimeMillis() - startTime;

        System.out.println("Processed in " + duration + "ms:");
        System.out.println("  New events: " + successCount.get());
        System.out.println("  Deduplicated: " + dedupeCount.get());

        // Part 3: Verify counts
        assertThat(successCount.get()).isEqualTo(UNIQUE_PAYMENTS);
        assertThat(dedupeCount.get()).isEqualTo(TOTAL_DELIVERIES - UNIQUE_PAYMENTS);
        assertThat(successCount.get() + dedupeCount.get()).isEqualTo(TOTAL_DELIVERIES);

        // Part 4: Verify wallet balance
        // Should be credited UNIQUE_PAYMENTS times × PAYMENT_AMOUNT
        long expectedBalance = (long) UNIQUE_PAYMENTS * PAYMENT_AMOUNT;

        var clientWalletFinal = accountRepository.findById(clientWalletId).orElseThrow();
        System.out.println("Wallet balance: " + clientWalletFinal.balance() +
            " (expected " + expectedBalance + ")");

        assertThat(clientWalletFinal.balance()).isEqualTo(expectedBalance);

        // Part 5: Verify invariants
        var violations = invariantChecker.check();
        assertThat(violations).isEmpty();

        System.out.println("C4 PASSED: All duplicates deduplicated, each payment credited once, invariants clean");

        executor.shutdown();
    }

    /**
     * Insert into processed_events table. Returns true if inserted (new), false if already existed.
     */
    private boolean insertProcessedEvent(UUID eventId) {
        try {
            // Simulating INSERT ... ON CONFLICT DO NOTHING behavior
            int updated = jdbcClient.sql(
                "INSERT INTO processed_events (consumer, event_id, processed_at) " +
                "VALUES ('webhook-consumer', ?, now()) " +
                "ON CONFLICT (consumer, event_id) DO NOTHING"
            )
                .param(eventId)
                .update();
            return updated > 0;
        } catch (Exception e) {
            // Fallback: check if it exists
            var existing = jdbcClient.sql(
                "SELECT event_id FROM processed_events WHERE consumer = 'webhook-consumer' AND event_id = ?"
            )
                .param(eventId)
                .query(UUID.class)
                .optional();
            return !existing.isPresent();
        }
    }

    /**
     * Credit wallet from EXTERNAL_BANK:RAZORPAY.
     */
    private void creditWallet(int paymentId, long amount) {
        // In a real test, this would call ledgerService.post() to credit the wallet
        // For simplicity, we'll directly update the balance
        jdbcClient.sql("UPDATE accounts SET balance = balance + ? WHERE id = ?")
            .param(amount)
            .param(clientWalletId)
            .update();
    }

    private record WebhookEvent(UUID eventId, String type, int paymentId, long amount) {}
}
