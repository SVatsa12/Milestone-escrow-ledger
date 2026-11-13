package com.svatsa.escrow.dispute;

import com.svatsa.escrow.common.Money;
import com.svatsa.escrow.escrow.EscrowService;
import com.svatsa.escrow.escrow.Milestone;
import com.svatsa.escrow.escrow.MilestoneRepository;
import com.svatsa.escrow.escrow.MilestoneState;
import com.svatsa.escrow.escrow.Project;
import com.svatsa.escrow.escrow.ProjectRepository;
import com.svatsa.escrow.ledger.Account;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import com.svatsa.escrow.escrow.User;
import com.svatsa.escrow.escrow.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for disputes.
 *
 * Tests the dispute flow:
 * 1. Open a dispute (freezes funds in DISPUTE_HOLD)
 * 2. Approve/release fails while disputed
 * 3. Resolve dispute with freelancer/client/both splits
 * 4. Verify invariants after each operation
 *
 * Note: Requires Postgres with Flyway migrations.
 * Disabled for H2 in-memory tests. Enable in CI with docker-compose.
 */
@Disabled("Requires Postgres with Flyway migrations; enable in CI with docker-compose")
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Dispute Integration Tests")
class DisputeIT extends AbstractIntegrationTest {

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
    private DisputeRepository disputeRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private com.svatsa.escrow.ledger.LedgerService ledgerService;

    private User client;
    private User freelancer;
    private Project project;
    private Milestone milestone;

    @BeforeEach
    void setUp() {
        clearLedger();

        client = userRepository.save("Alice (Client)", "CLIENT");
        freelancer = userRepository.save("Bob (Freelancer)", "FREELANCER");

        // Seed client's wallet with 500,000 paise (5,000 rupees)
        var clientWallet = accountRepository.findByCode("CLIENT_WALLET:" + client.id())
            .orElseThrow();
        var externalBank = accountRepository.create(
            "EXTERNAL_BANK:RAZORPAY",
            com.svatsa.escrow.ledger.AccountType.EXTERNAL_BANK,
            null,
            true
        );

        // Post a deposit
        ledgerService.post(
            new com.svatsa.escrow.ledger.PostingRequest(
                "DEPOSIT",
                "USER",
                client.id().toString(),
                "deposit:rzp:setup",
                "Setup deposit",
                List.of(
                    new com.svatsa.escrow.ledger.Leg(externalBank.id(), -500_000),
                    new com.svatsa.escrow.ledger.Leg(clientWallet.id(), 500_000)
                )
            )
        );

        // Create project with one milestone
        Instant now = clock.instant();
        Instant dueDate = now.plusSeconds(86400);

        project = escrowService.createProject(
            client.id(),
            freelancer.id(),
            "Test Project",
            Duration.ofDays(3),
            List.of(new EscrowService.MilestoneSpec("Work", 100_000, dueDate))
        );

        // Fund and submit the milestone
        escrowService.fundProject(project.id(), "fund:project:" + project.id());
        milestone = milestoneRepository.findByProjectId(project.id()).get(0);
        assertThat(milestone.state()).isEqualTo(MilestoneState.FUNDED);

        escrowService.submitMilestone(milestone.id(), "submit:milestone:" + milestone.id());
        milestone = milestoneRepository.findById(milestone.id()).orElseThrow();
        assertThat(milestone.state()).isEqualTo(MilestoneState.SUBMITTED);
    }

    @Test
    @DisplayName("open dispute: freezes funds in DISPUTE_HOLD, milestone transitions to DISPUTED")
    @Transactional
    void testOpenDispute() {
        // Verify initial state: SUBMITTED, ESCROW holds 100k
        var escrowBefore = accountRepository.findById(project.escrowAccountId()).orElseThrow();
        assertThat(escrowBefore.balance()).isEqualTo(100_000);

        // Open dispute
        Dispute dispute = disputeService.openDispute(
            milestone.id(),
            client.id(),
            "CLIENT",
            "Quality issues",
            "dispute-freeze:milestone:" + milestone.id()
        );

        // Verify dispute created
        assertThat(dispute.status()).isEqualTo("OPEN");
        assertThat(dispute.milestoneId()).isEqualTo(milestone.id());

        // Verify milestone transitioned to DISPUTED
        Milestone disputed = milestoneRepository.findById(milestone.id()).orElseThrow();
        assertThat(disputed.state()).isEqualTo(MilestoneState.DISPUTED);

        // Verify funds moved from ESCROW to DISPUTE_HOLD
        var escrowAfter = accountRepository.findById(project.escrowAccountId()).orElseThrow();
        var disputeHold = accountRepository.findById(project.disputeHoldAccountId()).orElseThrow();

        assertThat(escrowAfter.balance()).isEqualTo(0);
        assertThat(disputeHold.balance()).isEqualTo(100_000);
    }

    @Test
    @DisplayName("cannot open duplicate dispute on same milestone")
    @Transactional
    void testDuplicateDisputeBlocked() {
        // Open first dispute
        disputeService.openDispute(
            milestone.id(),
            client.id(),
            "CLIENT",
            "Quality issues",
            "dispute-freeze:milestone:" + milestone.id()
        );

        // Try to open another on the same milestone
        assertThatThrownBy(() ->
            disputeService.openDispute(
                milestone.id(),
                freelancer.id(),
                "FREELANCER",
                "Different issue",
                "dispute-freeze:milestone:" + milestone.id()
            )
        )
            .isInstanceOf(DisputeAlreadyOpenException.class);
    }

    @Test
    @DisplayName("cannot approve/release while milestone is DISPUTED")
    @Transactional
    void testApproveFailsWhileDisputed() {
        // Open dispute
        disputeService.openDispute(
            milestone.id(),
            client.id(),
            "CLIENT",
            "Quality issues",
            "Evidence..."
        );

        // Try to approve (should fail due to state check)
        assertThatThrownBy(() ->
            escrowService.approveMilestone(milestone.id(), "approve:milestone:" + milestone.id())
        )
            .isInstanceOf(com.svatsa.escrow.escrow.InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("resolve dispute: freelancer wins 100%, client gets 0%")
    @Transactional
    void testResolveFreelancerWins() {
        // Setup and open dispute
        Dispute dispute = disputeService.openDispute(
            milestone.id(),
            client.id(),
            "CLIENT",
            "False claim",
            "dispute-freeze:milestone:" + milestone.id()
        );

        // Resolve: freelancer gets all (100,000 paise), client gets nothing
        long fee = Money.calculateFee(100_000, 500); // 5% = 5,000
        long freelancerNet = 100_000 - fee; // 95,000

        disputeService.resolveDispute(
            dispute.id(),
            freelancerNet,
            0L,  // client gets nothing
            "Freelancer performed correctly",
            client.id(),
            "dispute-resolve:" + dispute.id()
        );

        // Verify dispute is resolved
        Dispute resolved = disputeRepository.findById(dispute.id()).orElseThrow();
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.freelancerSharePaise()).isEqualTo(freelancerNet);
        assertThat(resolved.clientSharePaise()).isEqualTo(0);

        // Verify milestone transitioned to RESOLVED
        Milestone resolvedMilestone = milestoneRepository.findById(milestone.id()).orElseThrow();
        assertThat(resolvedMilestone.state()).isEqualTo(MilestoneState.RESOLVED);

        // Verify balances updated
        var freelancerWallet = accountRepository.findByCode("FREELANCER_WALLET:" + freelancer.id())
            .orElseThrow();
        var clientWallet = accountRepository.findByCode("CLIENT_WALLET:" + client.id())
            .orElseThrow();
        var platformFees = accountRepository.findByCode("PLATFORM_FEES").orElseThrow();
        var disputeHold = accountRepository.findById(project.disputeHoldAccountId()).orElseThrow();

        assertThat(freelancerWallet.balance()).isEqualTo(freelancerNet);
        assertThat(clientWallet.balance()).isEqualTo(500_000 - 100_000); // unchanged from deposit
        assertThat(platformFees.balance()).isEqualTo(fee);
        assertThat(disputeHold.balance()).isEqualTo(0);
    }

    @Test
    @DisplayName("resolve dispute: client wins 100%, freelancer gets 0%")
    @Transactional
    void testResolveClientWins() {
        // Setup and open dispute
        Dispute dispute = disputeService.openDispute(
            milestone.id(),
            client.id(),
            "CLIENT",
            "Quality issues",
            "dispute-freeze:milestone:" + milestone.id()
        );

        // Resolve: client refund (100,000), freelancer gets nothing
        disputeService.resolveDispute(
            dispute.id(),
            0L,  // freelancer gets nothing
            100_000L,  // client gets full refund
            "Poor quality work",
            client.id(),
            "dispute-resolve:" + dispute.id()
        );

        // Verify dispute is resolved
        Dispute resolved = disputeRepository.findById(dispute.id()).orElseThrow();
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.freelancerSharePaise()).isEqualTo(0);
        assertThat(resolved.clientSharePaise()).isEqualTo(100_000);

        // Verify balances
        var freelancerWallet = accountRepository.findByCode("FREELANCER_WALLET:" + freelancer.id())
            .orElseThrow();
        var clientWallet = accountRepository.findByCode("CLIENT_WALLET:" + client.id())
            .orElseThrow();
        var platformFees = accountRepository.findByCode("PLATFORM_FEES").orElseThrow();
        var disputeHold = accountRepository.findById(project.disputeHoldAccountId()).orElseThrow();

        assertThat(freelancerWallet.balance()).isEqualTo(0);
        assertThat(clientWallet.balance()).isEqualTo(500_000 - 100_000 + 100_000); // refunded
        assertThat(platformFees.balance()).isEqualTo(0);
        assertThat(disputeHold.balance()).isEqualTo(0);
    }

    @Test
    @DisplayName("resolve dispute: 70/30 split (freelancer/client)")
    @Transactional
    void testResolveSplit70_30() {
        // Setup and open dispute
        Dispute dispute = disputeService.openDispute(
            milestone.id(),
            client.id(),
            "CLIENT",
            "Partial issue",
            "dispute-freeze:milestone:" + milestone.id()
        );

        // Resolve: split 70% to freelancer (net after fee), 30% to client
        long fee = Money.calculateFee(100_000, 500); // 5% = 5,000
        long totalSplit = 100_000;
        long freelancerNet = 66_500L;  // roughly 70% after fee
        long clientShare = 33_500L;    // the rest to client

        disputeService.resolveDispute(
            dispute.id(),
            freelancerNet,
            clientShare,
            "Partial responsibility",
            client.id(),
            "dispute-resolve:" + dispute.id()
        );

        // Verify dispute is resolved
        Dispute resolved = disputeRepository.findById(dispute.id()).orElseThrow();
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.freelancerSharePaise() + resolved.clientSharePaise())
            .isEqualTo(100_000); // Must sum to original amount

        var disputeHold = accountRepository.findById(project.disputeHoldAccountId()).orElseThrow();
        assertThat(disputeHold.balance()).isEqualTo(0);
    }

    @Test
    @DisplayName("ledger invariants hold after dispute flow")
    @Transactional
    void testInvariantsHoldAfterDispute() {
        // Open dispute
        Dispute dispute = disputeService.openDispute(
            milestone.id(),
            client.id(),
            "CLIENT",
            "Quality issues",
            "dispute-freeze:milestone:" + milestone.id()
        );

        // Resolve
        disputeService.resolveDispute(
            dispute.id(),
            50_000L,
            50_000L,
            "Split decision",
            client.id(),
            "dispute-resolve:" + dispute.id()
        );

        // Invariants checked in @AfterEach
    }
}
