package com.svatsa.escrow.dispute;

import com.svatsa.escrow.test.Actors;
import com.svatsa.escrow.common.DomainException;
import com.svatsa.escrow.escrow.AutoReleaseJob;
import com.svatsa.escrow.escrow.EscrowService;
import com.svatsa.escrow.escrow.Milestone;
import com.svatsa.escrow.escrow.MilestoneRepository;
import com.svatsa.escrow.escrow.MilestoneState;
import com.svatsa.escrow.escrow.Project;
import com.svatsa.escrow.escrow.User;
import com.svatsa.escrow.escrow.UserRepository;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.PostingRequest;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6 rules that had no test: a disputed milestone is never auto-released, and a split that
 * doesn't add up is 422 with nothing changed.
 */
@DisplayName("Dispute rules (P6)")
class DisputeRulesIT extends AbstractIntegrationTest {

    private static final Duration WINDOW = Duration.ofDays(3);

    @Autowired private EscrowService escrowService;
    @Autowired private DisputeService disputeService;
    @Autowired private AutoReleaseJob autoReleaseJob;
    @Autowired private UserRepository userRepository;
    @Autowired private MilestoneRepository milestoneRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private TransactionTemplate tx;

    private User client;
    private User freelancer;
    private Project project;
    private Milestone milestone;

    @BeforeEach
    void setUp() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        client = userRepository.save("c-" + run, "CLIENT");
        freelancer = userRepository.save("f-" + run, "FREELANCER");
        long wallet = accountRepository.findByCode("CLIENT_WALLET:" + client.id()).orElseThrow().id();
        long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();
        tx.executeWithoutResult(s -> ledgerService.post(new PostingRequest("TEST_SEED", "USER", client.id().toString(),
            "seed:test-" + run, "seed", List.of(new Leg(bank, -100_000), new Leg(wallet, 100_000)))));
        project = escrowService.createProject(Actors.client(client.id()), client.id(), freelancer.id(), "dispute-rules", WINDOW,
            List.of(new EscrowService.MilestoneSpec("m1", 60_000, Instant.now().plus(Duration.ofDays(30)))));
        escrowService.fundProject(clientOfProject(project.id()), project.id(), "fund-" + run);
        milestone = milestoneRepository.findByProjectId(project.id()).get(0);
        escrowService.submitMilestone(freelancerOfMilestone(milestone.id()), milestone.id(), "submit-" + run);
    }

    @Test
    @DisplayName("auto-release past the review window does NOT release a disputed milestone")
    void disputedMilestoneIsNotAutoReleased() {
        disputeService.openDispute(Actors.client(client.id()), milestone.id(), "not done", "dispute-" + milestone.id());

        clock.advance(WINDOW.plusDays(1));
        autoReleaseJob.runOnce();

        assertThat(milestoneRepository.findById(milestone.id()).orElseThrow().state()).isEqualTo(MilestoneState.DISPUTED);
        assertThat(accountRepository.findById(project.disputeHoldAccountId()).orElseThrow().balance()).isEqualTo(60_000);
        assertThat(accountRepository.findByCode("FREELANCER_WALLET:" + freelancer.id()).orElseThrow().balance()).isZero();
    }

    @Test
    @DisplayName("a split that doesn't add up is 422 and changes nothing")
    void splitThatDoesNotAddUpIs422() {
        Dispute dispute = disputeService.openDispute(Actors.client(client.id()), milestone.id(), "partial", "dispute-" + milestone.id());

        assertThatThrownBy(() -> disputeService.resolveDispute(newAdmin(), dispute.id(), 40_000L, 10_000L, "bad split",
                "resolve-" + dispute.id()))
            .isInstanceOfSatisfying(DomainException.class, e -> {
                assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED");
            });

        assertThat(milestoneRepository.findById(milestone.id()).orElseThrow().state()).isEqualTo(MilestoneState.DISPUTED);
        assertThat(accountRepository.findById(project.disputeHoldAccountId()).orElseThrow().balance()).isEqualTo(60_000);
    }
}
