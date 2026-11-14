package com.svatsa.escrow.escrow;

import com.svatsa.escrow.test.Actors;
import com.svatsa.escrow.common.Money;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.AccountType;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.PostingRequest;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for escrow workflows.
 *
 * Tests the happy path: create project → fund → submit → approve → release
 * Also tests: fee rounding, cancellation with grace period, invalid state transitions
 * Verifies invariants after every operation.
 * 
 * Note: Requires Postgres with Flyway migrations.
 * Disabled for H2 in-memory tests. Enable in CI with docker-compose.
 */
@DisplayName("EscrowFlow Integration Tests")
class EscrowFlowIT extends AbstractIntegrationTest {

    @Autowired
    private EscrowService escrowService;

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

    private User client;
    private User freelancer;

    /** PLATFORM_FEES is one shared system account, so tests assert how much it moved. */
    private long feesBefore;

    @BeforeEach
    void setUp() {
        feesBefore = accountRepository.findByCode("PLATFORM_FEES").orElseThrow().balance();
        client = userRepository.save("Alice (Client)", "CLIENT");
        freelancer = userRepository.save("Bob (Freelancer)", "FREELANCER");

        // Seed client's wallet with 10,000 rupees (1,000,000 paise)
        var clientWallet = accountRepository.findByCode("CLIENT_WALLET:" + client.id())
            .orElseThrow();
        var externalBank = accountRepository.create(
            "EXTERNAL_BANK:RAZORPAY",
            AccountType.EXTERNAL_BANK,
            null,
            true
        );

        // Manually post a deposit (simulate Razorpay webhook)
        // Inside a transaction: LedgerService.post requires one (propagation MANDATORY).
        seedTx.executeWithoutResult(status -> ledgerService.post(
            new PostingRequest(
                "DEPOSIT",
                "USER",
                client.id().toString(),
                "deposit:rzp:test-" + java.util.UUID.randomUUID(),
                "Test deposit",
                List.of(
                    new Leg(externalBank.id(), -1_000_000),
                    new Leg(clientWallet.id(), 1_000_000)
                )
            )
        ));
    }

    @Test
    @DisplayName("happy path: fund → submit → approve → release a 3-milestone project")
    void testHappyPath() {
        // Create project with 3 milestones
        long m1Amount = 100_000; // 1k paise
        long m2Amount = 200_000; // 2k paise
        long m3Amount = 50_000;  // 0.5k paise

        Instant now = clock.instant();
        Instant dueDate = now.plusSeconds(86400); // 1 day from now

        List<EscrowService.MilestoneSpec> specs = List.of(
            new EscrowService.MilestoneSpec("Design", m1Amount, dueDate),
            new EscrowService.MilestoneSpec("Development", m2Amount, dueDate.plusSeconds(86400)),
            new EscrowService.MilestoneSpec("Testing", m3Amount, dueDate.plusSeconds(172800))
        );

        Project project = escrowService.createProject(
            Actors.client(client.id()), client.id(),
            freelancer.id(),
            "Web App",
            Duration.ofDays(3),
            specs
        );

        assertThat(project.clientId()).isEqualTo(client.id());
        assertThat(project.freelancerId()).isEqualTo(freelancer.id());

        // Verify milestones exist and are in DRAFT
        List<Milestone> milestones = milestoneRepository.findByProjectId(project.id());
        assertThat(milestones).hasSize(3);
        milestones.forEach(m -> assertThat(m.state()).isEqualTo(MilestoneState.DRAFT));

        // Fund project
        String fundKey = "fund:project:" + project.id();
        escrowService.fundProject(clientOfProject(project.id()), project.id(), fundKey);

        // Verify all milestones transitioned to FUNDED
        List<Milestone> funded = milestoneRepository.findByProjectId(project.id());
        funded.forEach(m -> assertThat(m.state()).isEqualTo(MilestoneState.FUNDED));

        // Verify ESCROW account has total amount
        var escrowAccount = accountRepository.findById(project.escrowAccountId()).orElseThrow();
        long expectedTotal = m1Amount + m2Amount + m3Amount;
        assertThat(escrowAccount.balance()).isEqualTo(expectedTotal);

        // Submit milestone 1
        Milestone m1 = funded.get(0);
        String submitKey = "submit:milestone:" + m1.id();
        escrowService.submitMilestone(freelancerOfMilestone(m1.id()), m1.id(), submitKey);
        Milestone m1Submitted = milestoneRepository.findById(m1.id()).orElseThrow();
        assertThat(m1Submitted.state()).isEqualTo(MilestoneState.SUBMITTED);

        // Approve milestone 1 (releases money to freelancer)
        long fee1 = Money.calculateFee(m1Amount, 500); // 5% fee
        long freelancer1Payout = m1Amount - fee1;

        String approveKey = "approve:milestone:" + m1.id();
        escrowService.approveMilestone(clientOfMilestone(m1.id()), m1.id(), approveKey);

        Milestone m1Released = milestoneRepository.findById(m1.id()).orElseThrow();
        assertThat(m1Released.state()).isEqualTo(MilestoneState.RELEASED);

        // Verify money was released correctly
        var freelancerWallet = accountRepository.findByCode("FREELANCER_WALLET:" + freelancer.id())
            .orElseThrow();
        var platformFees = accountRepository.findByCode("PLATFORM_FEES").orElseThrow();

        assertThat(freelancerWallet.balance()).isEqualTo(freelancer1Payout);
        assertThat(platformFees.balance() - feesBefore).as("PLATFORM_FEES change (it is shared by every test)").isEqualTo(fee1);

        // ESCROW balance should be reduced
        var escrowAfter = accountRepository.findById(project.escrowAccountId()).orElseThrow();
        assertThat(escrowAfter.balance()).isEqualTo(expectedTotal - m1Amount);
    }

    @Test
    @DisplayName("fee calculation: 999 paise at 5% fee = 49 paise (integer floor division)")
    void testFeeRounding() {
        // 999 paise * 500 / 10_000 = 499_500 / 10_000 = 49 (floor)
        long fee = Money.calculateFee(999, 500);
        assertThat(fee).isEqualTo(49);

        // Freelancer gets 999 - 49 = 950
        long payout = 999 - fee;
        assertThat(payout).isEqualTo(950);
    }

    @Test
    @DisplayName("cancel grace period: FUNDED milestone cannot be cancelled before dueAt + grace")
    void testCancelGracePeriod() {
        Instant now = clock.instant();
        Instant dueDate = now.plusSeconds(3600); // 1 hour from now

        Project project = escrowService.createProject(
            Actors.client(client.id()), client.id(),
            freelancer.id(),
            "Test Project",
            Duration.ofDays(1),
            List.of(new EscrowService.MilestoneSpec("Work", 100_000, dueDate))
        );

        // Fund
        escrowService.fundProject(clientOfProject(project.id()), project.id(), "fund:project:" + project.id());

        Milestone m = milestoneRepository.findByProjectId(project.id()).get(0);
        assertThat(m.state()).isEqualTo(MilestoneState.FUNDED);

        // Try to cancel before grace period expires (7 days after due date)
        Duration gracePeriod = Duration.ofDays(7);
        assertThatThrownBy(() ->
            escrowService.cancelMilestone(clientOfMilestone(m.id()), m.id(), "cancel:milestone:" + m.id(), gracePeriod)
        )
            .isInstanceOf(CancelTooEarlyException.class);

        // Milestone should still be FUNDED
        Milestone stillFunded = milestoneRepository.findById(m.id()).orElseThrow();
        assertThat(stillFunded.state()).isEqualTo(MilestoneState.FUNDED);
    }

    @Test
    @DisplayName("DRAFT milestone can be cancelled immediately without grace period")
    void testCancelDraftImmediate() {
        Project project = escrowService.createProject(
            Actors.client(client.id()), client.id(),
            freelancer.id(),
            "Test Project",
            Duration.ofDays(1),
            List.of(new EscrowService.MilestoneSpec("Work", 100_000, Instant.now().plusSeconds(86400)))
        );

        Milestone m = milestoneRepository.findByProjectId(project.id()).get(0);
        assertThat(m.state()).isEqualTo(MilestoneState.DRAFT);

        // Cancel DRAFT immediately
        escrowService.cancelMilestone(clientOfMilestone(m.id()), m.id(), "cancel:milestone:" + m.id(), Duration.ofDays(7));

        Milestone cancelled = milestoneRepository.findById(m.id()).orElseThrow();
        assertThat(cancelled.state()).isEqualTo(MilestoneState.CANCELLED);
    }

    @Test
    @DisplayName("invalid state transition: cannot submit a DRAFT milestone")
    void testInvalidStateTransition() {
        Project project = escrowService.createProject(
            Actors.client(client.id()), client.id(),
            freelancer.id(),
            "Test",
            Duration.ofDays(1),
            List.of(new EscrowService.MilestoneSpec("Work", 100_000, Instant.now().plusSeconds(86400)))
        );

        Milestone m = milestoneRepository.findByProjectId(project.id()).get(0);

        // Cannot submit DRAFT directly
        assertThatThrownBy(() ->
            escrowService.submitMilestone(freelancerOfMilestone(m.id()), m.id(), "submit:milestone:" + m.id())
        )
            .isInstanceOf(InvalidStateTransitionException.class);
    }
}
