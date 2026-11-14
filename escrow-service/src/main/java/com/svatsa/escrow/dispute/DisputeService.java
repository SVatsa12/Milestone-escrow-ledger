package com.svatsa.escrow.dispute;

import com.svatsa.escrow.common.Actor;
import com.svatsa.escrow.common.Authz;
import com.svatsa.escrow.common.Money;
import com.svatsa.escrow.common.Role;
import com.svatsa.escrow.escrow.EscrowConfigurationProperties;
import com.svatsa.escrow.escrow.InvalidStateTransitionException;
import com.svatsa.escrow.escrow.Milestone;
import com.svatsa.escrow.escrow.MilestoneRepository;
import com.svatsa.escrow.escrow.MilestoneState;
import com.svatsa.escrow.escrow.Project;
import com.svatsa.escrow.escrow.ProjectRepository;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.LedgerKeys;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.PostingRequest;
import com.svatsa.escrow.outbox.OutboxWriter;
import com.svatsa.escrow.outbox.events.Events;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for dispute management: opening disputes (which freeze funds) and resolving them.
 * 
 * All money-moving operations follow AGENTS.md: single @Transactional method,
 * unique idempotency keys, proper leg construction with no zero-amount legs.
 */
@Service
public class DisputeService {

    private final DisputeRepository disputeRepository;
    private final MilestoneRepository milestoneRepository;
    private final ProjectRepository projectRepository;
    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;
    private final OutboxWriter outboxWriter;
    private final int feeBps;

    public DisputeService(
        DisputeRepository disputeRepository,
        MilestoneRepository milestoneRepository,
        ProjectRepository projectRepository,
        AccountRepository accountRepository,
        LedgerService ledgerService,
        OutboxWriter outboxWriter,
        EscrowConfigurationProperties config
    ) {
        this.disputeRepository = disputeRepository;
        this.milestoneRepository = milestoneRepository;
        this.projectRepository = projectRepository;
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
        this.outboxWriter = outboxWriter;
        this.feeBps = config.getFeeBps();
    }

    /**
     * Opens a dispute on a SUBMITTED milestone.
     * Freezes the milestone's escrow funds into DISPUTE_HOLD.
     * 
     * Money flow: ESCROW -A, DISPUTE_HOLD +A
     * State: SUBMITTED → DISPUTED
     * Key: dispute-freeze:milestone:{milestoneId}
     */
    @Transactional(rollbackFor = Exception.class)
    public Dispute openDispute(
        Actor actor,
        Long milestoneId,
        String reason,
        String requestKey
    ) {
        Milestone milestone = milestoneRepository.findById(milestoneId)
            .orElseThrow(() -> new IllegalArgumentException("Milestone not found: " + milestoneId));
        Project project = projectRepository.findById(milestone.projectId())
            .orElseThrow(() -> new IllegalArgumentException("Project not found: " + milestone.projectId()));
        // Either party of this project may dispute; nobody else.
        Authz.require(actor.is(Role.CLIENT, project.clientId()) || actor.is(Role.FREELANCER, project.freelancerId()),
            actor, "dispute milestone " + milestoneId);

        if (milestone.state() != MilestoneState.SUBMITTED) {
            throw InvalidStateTransitionException.invalidTransition(
                milestone.state(),
                MilestoneState.DISPUTED
            );
        }

        // Get accounts
        var escrowAccount = accountRepository.findById(project.escrowAccountId())
            .orElseThrow();
        var disputeHoldAccount = accountRepository.findById(project.disputeHoldAccountId())
            .orElseThrow();

        // Transition: SUBMITTED → DISPUTED
        boolean success = milestoneRepository.transitionState(
            milestoneId,
            MilestoneState.SUBMITTED,
            MilestoneState.DISPUTED,
            milestone.version(),
            actor,
            "dispute opened: " + reason,
            null
        );

        if (!success) {
            throw InvalidStateTransitionException.invalidTransition(
                MilestoneState.SUBMITTED,
                MilestoneState.DISPUTED
            );
        }

        // Freeze the money only after winning SUBMITTED → DISPUTED: that update locks the milestone
        // row before the accounts, the same order AutoReleaseJob uses (see approveMilestone).
        // Post: ESCROW → DISPUTE_HOLD
        ledgerService.post(
            new PostingRequest(
                LedgerKeys.DISPUTE_FREEZE,
                "MILESTONE",
                milestoneId.toString(),
                LedgerKeys.disputeFreeze(milestoneId),
                "Freeze dispute: " + reason,
                List.of(
                    new Leg(escrowAccount.id(), -milestone.amountPaise()),
                    new Leg(disputeHoldAccount.id(), milestone.amountPaise())
                )
            )
        );

        // Save dispute record (may throw DisputeAlreadyOpenException if constraint violated)
        Dispute dispute;
        try {
            dispute = disputeRepository.save(
                milestoneId,
                actor.id(),
                actor.role().name(),
                reason,
                null
            );
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new DisputeAlreadyOpenException(milestoneId);
        }

        // Emit DisputeOpened event
        outboxWriter.write(
            "Dispute",
            dispute.id().toString(),
            "DisputeOpened",
            "escrow.milestone-events",
            new Events.DisputeOpened(
                dispute.id(),
                milestoneId,
                project.id(),
                project.clientId(),
                project.freelancerId(),
                actor.id(),
                actor.role().name(),
                reason,
                milestone.title()
            )
        );

        return dispute;
    }

    /**
     * Resolves a dispute with an admin-approved split.
     * Validates that freelancer_share + client_share = milestone amount.
     * Applies the platform fee to the freelancer share only.
     * 
     * Money flow: DISPUTE_HOLD -A, FREELANCER_WALLET +(F-fee), PLATFORM_FEES +fee, CLIENT_WALLET +C
     * (Zero legs are skipped during posting)
     * State: DISPUTED → RESOLVED
     * Key: dispute-resolve:{disputeId}
     */
    @Transactional(rollbackFor = Exception.class)
    public void resolveDispute(
        Actor actor,
        Long disputeId,
        Long freelancerSharePaise,
        Long clientSharePaise,
        String resolutionNote,
        String requestKey
    ) {
        Authz.requireAdmin(actor, "resolve dispute " + disputeId);
        Dispute dispute = disputeRepository.findById(disputeId)
            .orElseThrow(() -> new IllegalArgumentException("Dispute not found: " + disputeId));

        if (!dispute.isOpen()) {
            // A second resolve is a state conflict (409), not a server error (it was a 500).
            throw new com.svatsa.escrow.common.DomainException("INVALID_STATE_TRANSITION",
                org.springframework.http.HttpStatus.CONFLICT, "Dispute is not open: " + disputeId);
        }

        Milestone milestone = milestoneRepository.findById(dispute.milestoneId())
            .orElseThrow();

        if (milestone.state() != MilestoneState.DISPUTED) {
            throw InvalidStateTransitionException.invalidTransition(
                milestone.state(),
                MilestoneState.RESOLVED
            );
        }

        // Validate split adds up to milestone amount
        if (freelancerSharePaise == null || clientSharePaise == null) {
            throw new IllegalArgumentException("Both shares must be provided");
        }

        long totalShare = freelancerSharePaise + clientSharePaise;
        if (totalShare != milestone.amountPaise()) {
            // P6: a split that doesn't add up is 422 (it was an IllegalArgumentException -> 400).
            throw new com.svatsa.escrow.common.DomainException("VALIDATION_FAILED",
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                String.format(
                    "Shares must sum to milestone amount. Got %d + %d = %d, expected %d",
                    freelancerSharePaise,
                    clientSharePaise,
                    totalShare,
                    milestone.amountPaise()
                )
            );
        }

        // Calculate fee (applies to freelancer share only)
        long fee = Money.calculateFee(freelancerSharePaise, feeBps);
        long freelancerNet = freelancerSharePaise - fee;

        // Get accounts
        Project project = projectRepository.findById(milestone.projectId())
            .orElseThrow();

        var disputeHoldAccount = accountRepository.findById(project.disputeHoldAccountId())
            .orElseThrow();

        String freelancerWalletCode = "FREELANCER_WALLET:" + project.freelancerId();
        var freelancerWallet = accountRepository.findByCode(freelancerWalletCode)
            .orElseThrow();

        var platformFeesAccount = accountRepository.findByCode("PLATFORM_FEES")
            .orElseThrow();

        String clientWalletCode = "CLIENT_WALLET:" + project.clientId();
        var clientWallet = accountRepository.findByCode(clientWalletCode)
            .orElseThrow();

        // Build legs (skip zero amounts)
        List<Leg> legs = new ArrayList<>();
        legs.add(new Leg(disputeHoldAccount.id(), -milestone.amountPaise()));

        if (freelancerNet > 0) {
            legs.add(new Leg(freelancerWallet.id(), freelancerNet));
        }
        if (fee > 0) {
            legs.add(new Leg(platformFeesAccount.id(), fee));
        }
        if (clientSharePaise > 0) {
            legs.add(new Leg(clientWallet.id(), clientSharePaise));
        }

        // Post: DISPUTE_HOLD split
        long entryId = ledgerService.post(
            new PostingRequest(
                LedgerKeys.DISPUTE_RESOLVE,
                "DISPUTE",
                disputeId.toString(),
                LedgerKeys.disputeResolve(disputeId),
                "Resolve dispute: " + resolutionNote,
                legs
            )
        ).entryId();

        // Update dispute record
        disputeRepository.resolve(
            disputeId,
            freelancerSharePaise,
            clientSharePaise,
            resolutionNote,
            actor.id(),
            entryId
        );

        // Transition: DISPUTED → RESOLVED
        boolean success = milestoneRepository.transitionState(
            dispute.milestoneId(),
            MilestoneState.DISPUTED,
            MilestoneState.RESOLVED,
            milestone.version(),
            actor,
            "dispute " + disputeId + " resolved by admin",
            entryId
        );

        if (!success) {
            throw InvalidStateTransitionException.invalidTransition(
                MilestoneState.DISPUTED,
                MilestoneState.RESOLVED
            );
        }

        // Emit DisputeResolved event
        outboxWriter.write(
            "Dispute",
            disputeId.toString(),
            "DisputeResolved",
            "escrow.milestone-events",
            new Events.DisputeResolved(
                disputeId,
                dispute.milestoneId(),
                milestone.projectId(),
                project.clientId(),
                project.freelancerId(),
                freelancerSharePaise,
                clientSharePaise,
                fee,
                milestone.title()
            )
        );
    }
}
