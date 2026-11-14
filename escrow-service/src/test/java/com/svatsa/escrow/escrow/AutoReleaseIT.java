package com.svatsa.escrow.escrow;

import com.svatsa.escrow.common.Money;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.PostingRequest;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6 auto-release, driven by the MutableClock (the old version never moved the clock, so the
 * job never had anything to release, and three of its tests asserted nothing).
 * Each test creates its own client, freelancer and project; "released exactly once" is proven
 * by the money: the project's ESCROW drops by the amount once, and the freelancer gets amount − fee.
 */
@DisplayName("Auto-release")
class AutoReleaseIT extends AbstractIntegrationTest {

    private static final Duration WINDOW = Duration.ofDays(3);
    private static final int FEE_BPS = 500;

    @Autowired private EscrowService escrowService;
    @Autowired private UserRepository userRepository;
    @Autowired private MilestoneRepository milestoneRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private TransactionTemplate tx;
    @Autowired private AutoReleaseJob autoReleaseJob;

    private User client;
    private User freelancer;

    @BeforeEach
    void setUp() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        client = userRepository.save("client-" + run, "CLIENT");
        freelancer = userRepository.save("freelancer-" + run, "FREELANCER");
        long wallet = accountRepository.findByCode("CLIENT_WALLET:" + client.id()).orElseThrow().id();
        long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();
        tx.executeWithoutResult(s -> ledgerService.post(new PostingRequest("DEPOSIT", "USER", client.id().toString(),
            "deposit:rzp:test-" + run, "seed", List.of(new Leg(bank, -1_000_000), new Leg(wallet, 1_000_000)))));
    }

    @Test
    @DisplayName("window − 1s: still SUBMITTED; +2s: RELEASED with exact balances")
    void releasesExactlyAtTheEndOfTheReviewWindow() {
        Project project = fundedProject(100_000);
        Milestone m = milestoneRepository.findByProjectId(project.id()).get(0);
        escrowService.submitMilestone(m.id(), "submit-" + m.id());
        long feesBefore = balance("PLATFORM_FEES");

        clock.advance(WINDOW.minusSeconds(1));
        autoReleaseJob.runOnce();
        assertThat(state(m)).isEqualTo(MilestoneState.SUBMITTED);

        clock.advance(Duration.ofSeconds(2));
        autoReleaseJob.runOnce();
        assertThat(state(m)).isEqualTo(MilestoneState.RELEASED);

        long fee = Money.calculateFee(100_000, FEE_BPS);
        assertThat(escrow(project)).isZero();
        assertThat(balance("FREELANCER_WALLET:" + freelancer.id())).isEqualTo(100_000 - fee);
        assertThat(balance("PLATFORM_FEES") - feesBefore).isEqualTo(fee);
    }

    @Test
    @DisplayName("every milestone past its window is released in one run")
    void releasesAllEligibleMilestones() {
        Project project = fundedProject(100_000, 200_000);
        for (Milestone m : milestoneRepository.findByProjectId(project.id())) {
            escrowService.submitMilestone(m.id(), "submit-" + m.id());
        }
        clock.advance(WINDOW.plusSeconds(1));
        autoReleaseJob.runOnce();

        assertThat(milestoneRepository.findByProjectId(project.id()))
            .allSatisfy(m -> assertThat(m.state()).isEqualTo(MilestoneState.RELEASED));
        assertThat(escrow(project)).isZero();
        assertThat(balance("FREELANCER_WALLET:" + freelancer.id()))
            .isEqualTo(300_000 - Money.calculateFee(100_000, FEE_BPS) - Money.calculateFee(200_000, FEE_BPS));
    }

    @Test
    @DisplayName("race: 20 approves and 5 auto-release runs on one milestone → exactly one release")
    void approveVersusAutoReleaseReleasesOnce() throws Exception {
        Project project = fundedProject(100_000);
        Milestone m = milestoneRepository.findByProjectId(project.id()).get(0);
        escrowService.submitMilestone(m.id(), "submit-" + m.id());
        clock.advance(WINDOW.plusSeconds(1));   // eligible for both paths

        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(25)) {
            for (int i = 0; i < 20; i++) {
                final int n = i;
                pool.submit(() -> {
                    await(start);
                    try {
                        escrowService.approveMilestone(m.id(), "approve-" + m.id() + "-" + n);
                    } catch (InvalidStateTransitionException expected) {
                        // lost the race: correct
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                });
            }
            for (int i = 0; i < 5; i++) {
                pool.submit(() -> {
                    await(start);
                    try {
                        autoReleaseJob.runOnce();
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(unexpected).isEmpty();
        assertThat(state(m)).isEqualTo(MilestoneState.RELEASED);
        assertThat(escrow(project)).as("escrow left the project exactly once").isZero();
        assertThat(balance("FREELANCER_WALLET:" + freelancer.id()))
            .as("freelancer paid exactly once").isEqualTo(100_000 - Money.calculateFee(100_000, FEE_BPS));
    }

    @Test
    @DisplayName("two runOnce() in parallel over 200 eligible milestones → each released exactly once")
    void parallelJobsReleaseEachMilestoneOnce() throws Exception {
        long[] amounts = new long[200];
        java.util.Arrays.fill(amounts, 1_000);
        Project project = fundedProject(amounts);
        for (Milestone m : milestoneRepository.findByProjectId(project.id())) {
            escrowService.submitMilestone(m.id(), "submit-" + m.id());
        }
        clock.advance(WINDOW.plusSeconds(1));

        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    await(start);
                    try {
                        autoReleaseJob.runOnce();
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(errors).isEmpty();
        assertThat(milestoneRepository.findByProjectId(project.id()))
            .hasSize(200)
            .allSatisfy(m -> assertThat(m.state()).isEqualTo(MilestoneState.RELEASED));
        assertThat(escrow(project)).isZero();
        assertThat(balance("FREELANCER_WALLET:" + freelancer.id()))
            .isEqualTo(200 * (1_000 - Money.calculateFee(1_000, FEE_BPS)));
    }

    // --- helpers -----------------------------------------------------------------------------

    private Project fundedProject(long... amounts) {
        Instant due = clock.instant().plus(Duration.ofDays(30));
        List<EscrowService.MilestoneSpec> specs = new ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            specs.add(new EscrowService.MilestoneSpec("m" + (i + 1), amounts[i], due));
        }
        Project project = escrowService.createProject(client.id(), freelancer.id(), "auto-release", WINDOW, specs);
        escrowService.fundProject(project.id(), "fund-" + project.id());
        return project;
    }

    private MilestoneState state(Milestone m) {
        return milestoneRepository.findById(m.id()).orElseThrow().state();
    }

    private long escrow(Project p) {
        return accountRepository.findById(p.escrowAccountId()).orElseThrow().balance();
    }

    private long balance(String code) {
        return accountRepository.findByCode(code).orElseThrow().balance();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
