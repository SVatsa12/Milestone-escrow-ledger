package com.svatsa.escrow.dispute;

import com.svatsa.escrow.common.Money;
import com.svatsa.escrow.escrow.EscrowConfigurationProperties;
import com.svatsa.escrow.escrow.InvalidStateTransitionException;
import com.svatsa.escrow.escrow.Milestone;
import com.svatsa.escrow.escrow.MilestoneRepository;
import com.svatsa.escrow.escrow.MilestoneState;
import com.svatsa.escrow.escrow.Project;
import com.svatsa.escrow.escrow.ProjectRepository;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.PostingRequest;
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
    private final int feeBps;

    public DisputeService(
        DisputeRepository disputeRepository,
        MilestoneRepository milestoneRepository,
        ProjectRepository projectRepository,
        AccountRepository accountRepository,
        LedgerService ledgerService,
        EscrowConfigurationProperties config
    ) {
        this.disputeRepository = disputeRepository;
        this.milestoneRepository = milestoneRepository;
        this.projectRepository = projectRepository;
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
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
        Long milestoneId,
        Long openedBy,
        String openedByRole,
        String reason,
        String idempotencyKey
    ) {
        Milestone milestone = milestoneRepository.findById(milestoneId)
            .orElseThrow(() -> new IllegalArgumentException("Milestone not found: " + milestoneId));

        if (milestone.state() != MilestoneState.SUBMITTED) {
            throw InvalidStateTransitionException.invalidTransition(
                milestone.state(),
                MilestoneState.DISPUTED
            );
        }

        // Get project and accounts
        Project project = projectRepository.findById(milestone.projectId())
            .orElseThrow();

        var escrowAccount = accountRepository.findById(project.escrowAccountId())
            .orElseThrow();
        var disputeHoldAccount = accountRepository.findById(project.disputeHoldAccountId())
            .orElseThrow();

        // Post: ESCROW → DISPUTE_HOLD
        ledgerService.post(
            new PostingRequest(
                "TRANSFER",
                "MILESTONE",
                milestoneId.toString(),
                idempotencyKey,
                "Freeze dispute: " + reason,
                List.of(
                    new Leg(escrowAccount.id(), -milestone.amountPaise()),
                    new Leg(disputeHoldAccount.id(), milestone.amountPaise())
                )
            )
        );

        // Transition: SUBMITTED → DISPUTED
        boolean success = milestoneRepository.transitionState(
            milestoneId,
            MilestoneState.SUBMITTED,
            MilestoneState.DISPUTED,
            milestone.version()
        );

        if (!success) {
            throw InvalidStateTransitionException.invalidTransition(
                MilestoneState.SUBMITTED,
                MilestoneState.DISPUTED
            );
        }

        // Save dispute record (may throw DisputeAlreadyOpenException if constraint violated)
        Dispute dispute;
        try {
            dispute = disputeRepository.save(
                milestoneId,
                openedBy,
                openedByRole,
                reason,
                null
            );
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new DisputeAlreadyOpenException(milestoneId);
        }

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
        Long disputeId,
        Long freelancerSharePaise,
        Long clientSharePaise,
        String resolutionNote,
        Long resolvedBy,
        String idempotencyKey
    ) {
        Dispute dispute = disputeRepository.findById(disputeId)
            .orElseThrow(() -> new IllegalArgumentException("Dispute not found: " + disputeId));

        if (!dispute.isOpen()) {
            throw new IllegalStateException("Dispute is not open: " + disputeId);
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
            throw new IllegalArgumentException(
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
        ledgerService.post(
            new PostingRequest(
                "TRANSFER",
                "DISPUTE",
                disputeId.toString(),
                idempotencyKey,
                "Resolve dispute: " + resolutionNote,
                legs
            )
        );

        // Update dispute record
        disputeRepository.resolve(
            disputeId,
            freelancerSharePaise,
            clientSharePaise,
            resolutionNote,
            resolvedBy,
            null // TODO: get journal_entry_id from ledger
        );

        // Transition: DISPUTED → RESOLVED
        boolean success = milestoneRepository.transitionState(
            dispute.milestoneId(),
            MilestoneState.DISPUTED,
            MilestoneState.RESOLVED,
            milestone.version()
        );

        if (!success) {
            throw InvalidStateTransitionException.invalidTransition(
                MilestoneState.DISPUTED,
                MilestoneState.RESOLVED
            );
        }
    }
}
