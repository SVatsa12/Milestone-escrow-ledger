package com.svatsa.escrow.escrow;

import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.InsufficientFundsException;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P5 rules that had no test: approving twice, funding without enough money, and the
 * cancel-after-grace refund.
 */
@DisplayName("Escrow rules (P5)")
class EscrowRulesIT extends AbstractIntegrationTest {

    private static final Duration GRACE = Duration.ofDays(3);

    @Autowired private EscrowService escrowService;
    @Autowired private UserRepository userRepository;
    @Autowired private MilestoneRepository milestoneRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private TransactionTemplate tx;

    private User client;
    private User freelancer;

    @BeforeEach
    void setUp() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        client = userRepository.save("c-" + run, "CLIENT");
        freelancer = userRepository.save("f-" + run, "FREELANCER");
    }

    @Test
    @DisplayName("approve twice with different keys: the second is a state conflict and only one release exists")
    void approvingTwiceReleasesOnce() {
        deposit(100_000);
        Milestone m = submitted(fundedProject(50_000));

        escrowService.approveMilestone(m.id(), "approve-1-" + m.id());
        assertThatThrownBy(() -> escrowService.approveMilestone(m.id(), "approve-2-" + m.id()))
            .isInstanceOf(InvalidStateTransitionException.class);

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM journal_entries WHERE reference_type = 'MILESTONE' AND reference_id = ?")
            .param(String.valueOf(m.id())).query(Long.class).single())
            .as("release entries for the milestone").isEqualTo(1);
        assertThat(state(m)).isEqualTo(MilestoneState.RELEASED);
    }

    @Test
    @DisplayName("funding with too little in the wallet changes nothing; milestones stay DRAFT")
    void fundingWithInsufficientFundsChangesNothing() {
        deposit(10_000);
        Project project = escrowService.createProject(client.id(), freelancer.id(), "too-big", Duration.ofDays(5),
            List.of(new EscrowService.MilestoneSpec("m1", 50_000, Instant.now().plus(Duration.ofDays(30)))));

        assertThatThrownBy(() -> escrowService.fundProject(project.id(), "fund-" + project.id()))
            .isInstanceOf(InsufficientFundsException.class);

        assertThat(milestoneRepository.findByProjectId(project.id()))
            .allSatisfy(m -> assertThat(m.state()).isEqualTo(MilestoneState.DRAFT));
        assertThat(accountRepository.findById(project.escrowAccountId()).orElseThrow().balance()).isZero();
        assertThat(wallet()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("cancel before due + grace is CANCEL_TOO_EARLY; after it the money is refunded")
    void cancelIsRefusedUntilTheGracePeriodEndsThenRefunds() {
        deposit(100_000);
        Instant due = clock.instant().plus(Duration.ofDays(2));
        Project project = escrowService.createProject(client.id(), freelancer.id(), "late", Duration.ofDays(5),
            List.of(new EscrowService.MilestoneSpec("m1", 40_000, due)));
        escrowService.fundProject(project.id(), "fund-" + project.id());
        Milestone m = milestoneRepository.findByProjectId(project.id()).get(0);

        assertThatThrownBy(() -> escrowService.cancelMilestone(m.id(), "cancel-1-" + m.id(), GRACE))
            .isInstanceOf(CancelTooEarlyException.class);
        assertThat(state(m)).isEqualTo(MilestoneState.FUNDED);

        clock.advance(Duration.ofDays(2).plus(GRACE).plusSeconds(1));
        escrowService.cancelMilestone(m.id(), "cancel-2-" + m.id(), GRACE);

        assertThat(state(m)).isEqualTo(MilestoneState.REFUNDED);
        assertThat(accountRepository.findById(project.escrowAccountId()).orElseThrow().balance()).isZero();
        assertThat(wallet()).as("all 100,000 back in the wallet").isEqualTo(100_000);
    }

    // --- helpers -----------------------------------------------------------------------------

    private void deposit(long amount) {
        long wallet = accountRepository.findByCode("CLIENT_WALLET:" + client.id()).orElseThrow().id();
        long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();
        tx.executeWithoutResult(s -> ledgerService.post(new PostingRequest("DEPOSIT", "USER", client.id().toString(),
            "deposit:rzp:test-" + UUID.randomUUID(), "seed", List.of(new Leg(bank, -amount), new Leg(wallet, amount)))));
    }

    private Project fundedProject(long amount) {
        Project project = escrowService.createProject(client.id(), freelancer.id(), "rules", Duration.ofDays(5),
            List.of(new EscrowService.MilestoneSpec("m1", amount, Instant.now().plus(Duration.ofDays(30)))));
        escrowService.fundProject(project.id(), "fund-" + project.id());
        return project;
    }

    private Milestone submitted(Project project) {
        Milestone m = milestoneRepository.findByProjectId(project.id()).get(0);
        escrowService.submitMilestone(m.id(), "submit-" + m.id());
        return m;
    }

    private MilestoneState state(Milestone m) {
        return milestoneRepository.findById(m.id()).orElseThrow().state();
    }

    private long wallet() {
        return accountRepository.findByCode("CLIENT_WALLET:" + client.id()).orElseThrow().balance();
    }
}
