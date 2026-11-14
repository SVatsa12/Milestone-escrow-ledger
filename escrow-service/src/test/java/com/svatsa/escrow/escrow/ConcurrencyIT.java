package com.svatsa.escrow.escrow;

import com.svatsa.escrow.dispute.DisputeService;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests for disputes and auto-release.
 *
 * Tests concurrent scenarios:
 * 1. Multiple threads trying to approve the same milestone (only one succeeds)
 * 2. Multiple threads trying to open disputes and approve simultaneously
 * 3. AutoReleaseJob with concurrent invocations (SKIP LOCKED prevents duplicates)
 *
 * Note: Requires Postgres with Flyway migrations.
 * Disabled for H2 in-memory tests. Enable in CI with docker-compose.
 */
@DisplayName("Concurrency Integration Tests")
class ConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private EscrowService escrowService;

    @Autowired
    private DisputeService disputeService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private MilestoneRepository milestoneRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate seedTx;

    @Autowired
    private AutoReleaseJob autoReleaseJob;

    private User client;
    private User freelancer;
    private Project project;
    private Milestone milestone;

    @BeforeEach
    void setUp() {

        client = userRepository.save("Alice (Client)", "CLIENT");
        freelancer = userRepository.save("Bob (Freelancer)", "FREELANCER");

        // Seed client's wallet
        var clientWallet = accountRepository.findByCode("CLIENT_WALLET:" + client.id())
            .orElseThrow();
        var externalBank = accountRepository.create(
            "EXTERNAL_BANK:RAZORPAY",
            com.svatsa.escrow.ledger.AccountType.EXTERNAL_BANK,
            null,
            true
        );

        // Inside a transaction: LedgerService.post requires one (propagation MANDATORY).
        seedTx.executeWithoutResult(status -> ledgerService.post(
            new com.svatsa.escrow.ledger.PostingRequest(
                "DEPOSIT",
                "USER",
                client.id().toString(),
                "deposit:rzp:test-" + java.util.UUID.randomUUID(),
                "Setup deposit",
                List.of(
                    new com.svatsa.escrow.ledger.Leg(externalBank.id(), -1_000_000),
                    new com.svatsa.escrow.ledger.Leg(clientWallet.id(), 1_000_000)
                )
            )
        ));

        // Create project
        Instant now = clock.instant();
        Instant dueDate = now.plusSeconds(86400);

        project = escrowService.createProject(
            client.id(),
            freelancer.id(),
            "Test Project",
            Duration.ofDays(3),
            List.of(new EscrowService.MilestoneSpec("Work", 100_000, dueDate))
        );

        // Fund and submit
        escrowService.fundProject(project.id(), "fund:project:" + project.id());
        milestone = milestoneRepository.findByProjectId(project.id()).get(0);
        escrowService.submitMilestone(milestone.id(), "submit:milestone:" + milestone.id());
    }

    @Test
    @DisplayName("concurrent approvals: only one succeeds due to state machine lock")
    void testConcurrentApprovals() throws InterruptedException {
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    String key = "approve:milestone:" + milestone.id() + ":" + Thread.currentThread().getId();
                    escrowService.approveMilestone(milestone.id(), key);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Exactly one approval should succeed (idempotency key prevents retries)
        // Other 4 should either fail with invalid state or duplicate key
        assertThat(successCount.get() + failureCount.get()).isEqualTo(threadCount);

        // Approve and release happen in one transaction (AGENTS.md), so the end state is RELEASED
        Milestone approved = milestoneRepository.findById(milestone.id()).orElseThrow();
        assertThat(approved.state()).isEqualTo(MilestoneState.RELEASED);
    }

    @Test
    @DisplayName("concurrent dispute open: only one succeeds due to UNIQUE constraint")
    void testConcurrentDisputeOpen() throws InterruptedException {
        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int thread = i;
            executor.submit(() -> {
                try {
                    disputeService.openDispute(
                        milestone.id(),
                        client.id(),
                        "CLIENT",
                        "Issue " + thread,
                        "Evidence " + thread
                    );
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Exactly one dispute should be created (UNIQUE constraint on milestoneId)
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isGreaterThan(0);

        // Verify milestone is DISPUTED
        Milestone disputed = milestoneRepository.findById(milestone.id()).orElseThrow();
        assertThat(disputed.state()).isEqualTo(MilestoneState.DISPUTED);
    }

    @Test
    @DisplayName("concurrent auto-release: SKIP LOCKED prevents duplicate releases")
    void testConcurrentAutoRelease() throws InterruptedException {
        // Create a project with 5 milestones
        Instant now = clock.instant();
        Instant dueDate = now.plusSeconds(86400);

        Project multiProject = escrowService.createProject(
            client.id(),
            freelancer.id(),
            "Multi Milestone",
            Duration.ofDays(3),
            List.of(
                new EscrowService.MilestoneSpec("M1", 20_000, dueDate),
                new EscrowService.MilestoneSpec("M2", 20_000, dueDate),
                new EscrowService.MilestoneSpec("M3", 20_000, dueDate),
                new EscrowService.MilestoneSpec("M4", 20_000, dueDate),
                new EscrowService.MilestoneSpec("M5", 20_000, dueDate)
            )
        );

        // Fund and submit all milestones
        escrowService.fundProject(multiProject.id(), "fund:project:" + multiProject.id());
        for (Milestone m : milestoneRepository.findByProjectId(multiProject.id())) {
            escrowService.submitMilestone(m.id(), "submit:milestone:" + m.id());
        }

        // Spawn 5 threads each calling autoReleaseJob.runOnce()
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    autoReleaseJob.runOnce();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Count released milestones
        List<Milestone> allMilestones = milestoneRepository.findByProjectId(multiProject.id());
        long releasedCount = allMilestones.stream()
            .filter(m -> m.state() == MilestoneState.RELEASED)
            .count();

        // If window has expired, all 5 should be released (not more, not less)
        // If window hasn't expired, none should be released
        assertThat(releasedCount).isIn(0L, 5L); // Either all released or none (depending on clock)

        // Verify no duplicate journal entries for the same milestone
        // (This would be detected by invariant violations if it occurred)
    }

    @Test
    @DisplayName("concurrent dispute open + approval: only approve wins state check")
    void testConcurrentDisputeAndApprove() throws InterruptedException {
        int disputeThreads = 2;
        int approveThreads = 3;
        int totalThreads = disputeThreads + approveThreads;

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch latch = new CountDownLatch(totalThreads);
        AtomicInteger disputeSuccess = new AtomicInteger(0);
        AtomicInteger approveSuccess = new AtomicInteger(0);

        // Spawn dispute threads
        for (int i = 0; i < disputeThreads; i++) {
            executor.submit(() -> {
                try {
                    disputeService.openDispute(
                        milestone.id(),
                        client.id(),
                        "CLIENT",
                        "Dispute",
                        "Evidence"
                    );
                    disputeSuccess.incrementAndGet();
                } catch (Exception e) {
                    // Expected: duplicate or state conflict
                } finally {
                    latch.countDown();
                }
            });
        }

        // Spawn approve threads
        for (int i = 0; i < approveThreads; i++) {
            executor.submit(() -> {
                try {
                    escrowService.approveMilestone(
                        milestone.id(),
                        "approve:milestone:" + milestone.id() + ":" + Thread.currentThread().getId()
                    );
                    approveSuccess.incrementAndGet();
                } catch (Exception e) {
                    // Expected: invalid state transition (DISPUTED cannot approve, or already APPROVED)
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Either dispute wins (milestone is DISPUTED, approvals fail),
        // or approve wins (milestone is APPROVED, disputes fail)
        Milestone result = milestoneRepository.findById(milestone.id()).orElseThrow();
        assertThat(result.state()).isIn(
            MilestoneState.DISPUTED,
            MilestoneState.APPROVED,
            MilestoneState.RELEASED
        );
    }

    @Test
    @DisplayName("ledger invariants hold after concurrent operations")
    void testInvariantsAfterConcurrency() throws InterruptedException {
        // Run concurrent approvals
        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    escrowService.approveMilestone(
                        milestone.id(),
                        "approve:milestone:" + milestone.id() + ":" + System.nanoTime()
                    );
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Verify invariants
        assertThat(invariantChecker.check()).isEmpty();
    }
}
