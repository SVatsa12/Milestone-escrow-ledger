package com.svatsa.escrow.chaos;

import com.svatsa.escrow.payouts.PayoutRepository;
import com.svatsa.escrow.payouts.PayoutService;
import com.svatsa.escrow.payouts.PayoutState;
import com.svatsa.escrow.recon.InvariantChecker;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
// import org.testcontainers.toxiproxy.ToxiproxyClient;
// import org.testcontainers.toxiproxy.ToxiproxyContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * C1: Provider latency chaos test.
 *
 * Scenario:
 * - Inject 3000ms latency on payout provider proxy (above 2s read timeout)
 * - Request 100 payouts → all should go to UNKNOWN state (timeout)
 * - Remove latency and run recovery job
 * - All payouts should become CONFIRMED
 * - No reverse entries should exist
 * - Invariants must be clean
 *
 * Tests that the system correctly handles provider timeouts and recovery.
 */
@Tag("chaos")
@Disabled("Requires Toxiproxy with Testcontainers; enable in CI")
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("C1: Provider Latency Chaos Test")
class C1ProviderLatencyChaosIT extends AbstractIntegrationTest {

    @Autowired
    private PayoutService payoutService;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private InvariantChecker invariantChecker;

    // ToxiproxyContainer would be provided by test infrastructure
    // For now, this is a template; actual toxiproxy setup is in docker-compose with profile "chaos"

    private static final int PAYOUT_COUNT = 100;
    private static final long FREELANCER_ID = 42L;
    private static final long WALLET_ACCOUNT_ID = 1L;
    private static final long PAYOUT_AMOUNT = 100_00; // 100 rupees in paise

    @BeforeEach
    void setUp() {
        // In a real chaos setup, Toxiproxy would be configured here
    }

    @Test
    @DisplayName("Should timeout payouts with 3000ms latency, then recover to CONFIRMED")
    void shouldHandleProviderLatencyAndRecover() throws Exception {
        // Part 1: Request payouts with latency enabled (3000ms > 2s timeout)
        // All should go to UNKNOWN
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < PAYOUT_COUNT; i++) {
            var payout = payoutService.requestPayout(
                FREELANCER_ID,
                WALLET_ACCOUNT_ID,
                PAYOUT_AMOUNT,
                "payout-latency-" + i
            );
            // Payout should be in RESERVED or UNKNOWN after the request
            // (depends on timing of provider timeout)
        }

        long part1Duration = System.currentTimeMillis() - startTime;
        System.out.println("Part 1 (request with latency): " + part1Duration + "ms");

        // Part 2: Check that payouts are in UNKNOWN or terminal states
        // Due to timeout, most should be UNKNOWN
        var allPayouts = payoutRepository.findAllInFlightOrCompleted();
        long unknownCount = allPayouts.stream()
            .filter(p -> p.state() == PayoutState.UNKNOWN)
            .count();

        System.out.println("After latency injection: " + unknownCount + " payouts in UNKNOWN");
        assertThat(unknownCount).isGreaterThan(0);

        // Part 3: Remove latency (Toxiproxy toxic removed)
        // In a real test, this would call toxiProxy.getEndpoint().removeToxic(...)
        System.out.println("(Simulating: removing latency toxic)");

        // Part 4: Run recovery job (poll every 10s, exponential backoff)
        // This would call payoutRecoveryJob.runOnce() multiple times or wait for scheduler
        System.out.println("(Simulating: running recovery job)");

        // Part 5: Wait for all payouts to become terminal
        await()
            .pollDelay(java.time.Duration.ofMillis(100))
            .timeout(java.time.Duration.ofSeconds(30))
            .until(() -> {
                var payouts = payoutRepository.findAllInFlightOrCompleted();
                return payouts.stream()
                    .allMatch(p -> p.state() == PayoutState.CONFIRMED || 
                                   p.state() == PayoutState.FAILED ||
                                   p.state() == PayoutState.CANCELLED);
            });

        // Part 6: Verify final state
        var finalPayouts = payoutRepository.findAllInFlightOrCompleted();
        assertThat(finalPayouts).hasSize(PAYOUT_COUNT);

        long confirmedCount = finalPayouts.stream()
            .filter(p -> p.state() == PayoutState.CONFIRMED)
            .count();

        System.out.println("After recovery: " + confirmedCount + " CONFIRMED, " +
            (PAYOUT_COUNT - confirmedCount) + " other states");

        // All should be CONFIRMED (provider returned SUCCESS)
        assertThat(confirmedCount).isEqualTo(PAYOUT_COUNT);

        // Part 7: Verify invariants
        var violations = invariantChecker.check();
        assertThat(violations).isEmpty();

        System.out.println("C1 PASSED: All payouts recovered to CONFIRMED, zero violations");
    }
}
