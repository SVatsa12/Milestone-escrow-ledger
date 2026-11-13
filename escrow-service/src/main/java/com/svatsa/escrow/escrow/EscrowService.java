package com.svatsa.escrow.escrow;

import com.svatsa.escrow.common.Money;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.AccountType;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.PostingRequest;
import com.svatsa.escrow.outbox.OutboxWriter;
import com.svatsa.escrow.outbox.events.Events;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for escrow operations: project creation, funding, submission, approval, release, and cancellation.
 * Every money-moving operation is one @Transactional method.
 * AGENTS.md invariants enforced:
 * - Idempotency keys prevent double-spending (via ledger's built-in idempotency_key)
 * - State transitions via conditional UPDATE ... WHERE state = ?
 * - Fee calculation: fee = amount * feeBps / 10_000 (integer floor)
 * - All amounts in paise (long), never double/float
 */
@Service
public class EscrowService {

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final MilestoneRepository milestoneRepository;
    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;
    private final OutboxWriter outboxWriter;
    private final Clock clock;
    private final int feeBps;

    public EscrowService(
        UserRepository userRepository,
        ProjectRepository projectRepository,
        MilestoneRepository milestoneRepository,
        AccountRepository accountRepository,
        LedgerService ledgerService,
        OutboxWriter outboxWriter,
        Clock clock,
        EscrowConfigurationProperties config
    ) {
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.milestoneRepository = milestoneRepository;
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
        this.feeBps = config.getFeeBps();
    }

    /**
     * Creates a new project and its milestones.
     * Creates ESCROW and DISPUTE_HOLD accounts in the ledger.
     * Milestones start in DRAFT state.
     */
    @Transactional(rollbackFor = Exception.class)
    public Project createProject(
        Long clientId,
        Long freelancerId,
        String title,
        Duration reviewWindow,
        List<MilestoneSpec> milestoneSpecs
    ) {
        // Verify users exist
        userRepository.findById(clientId)
            .orElseThrow(() -> new IllegalArgumentException("Client not found: " + clientId));
        userRepository.findById(freelancerId)
            .orElseThrow(() -> new IllegalArgumentException("Freelancer not found: " + freelancerId));

        // Create ESCROW and DISPUTE_HOLD accounts
        var escrowAccount = accountRepository.create(
            "ESCROW:" + System.currentTimeMillis(), // temp code, will be updated
            AccountType.ESCROW,
            null,
            false
        );

        var disputeHoldAccount = accountRepository.create(
            "DISPUTE_HOLD:" + System.currentTimeMillis(), // temp code
            AccountType.DISPUTE_HOLD,
            null,
            false
        );

        // Save project
        Project project = projectRepository.save(
            clientId,
            freelancerId,
            title,
            reviewWindow,
            escrowAccount.id(),
            disputeHoldAccount.id()
        );

        // Create milestones in DRAFT state
        for (int i = 0; i < milestoneSpecs.size(); i++) {
            MilestoneSpec spec = milestoneSpecs.get(i);
            milestoneRepository.save(
                project.id(),
                i + 1,
                spec.title(),
                spec.amountPaise(),
                spec.dueAt()
            );
        }

        return project;
    }

    /**
     * Funds a project: transfers from CLIENT_WALLET to ESCROW.
     * All milestones transition to FUNDED.
     * Uses idempotency key to prevent double-funding.
     *
     * Money flow: CLIENT_WALLET -ΣA, ESCROW +ΣA
     * Journal entry with idempotency_key = "fund:project:{projectId}"
     */
    @Transactional(rollbackFor = Exception.class)
    public void fundProject(Long projectId, String idempotencyKey) {
        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        List<Milestone> milestones = milestoneRepository.findByProjectId(projectId);
        long totalAmount = milestones.stream()
            .filter(m -> m.state() == MilestoneState.DRAFT)
            .mapToLong(Milestone::amountPaise)
            .sum();

        if (totalAmount <= 0) {
            throw new IllegalArgumentException("No DRAFT milestones to fund");
        }

        // Create journal entry: CLIENT_WALLET -> ESCROW
        String clientWalletCode = "CLIENT_WALLET:" + project.clientId();
        var clientWallet = accountRepository.findByCode(clientWalletCode)
            .orElseThrow(() -> new IllegalArgumentException("Client wallet not found: " + clientWalletCode));

        var escrowAccount = accountRepository.findById(project.escrowAccountId())
            .orElseThrow(() -> new IllegalArgumentException("Escrow account not found"));

        ledgerService.post(
            new PostingRequest(
                "TRANSFER",
                "PROJECT",
                projectId.toString(),
                idempotencyKey,
                "Fund project " + projectId,
                List.of(
                    new Leg(clientWallet.id(), -totalAmount),
                    new Leg(escrowAccount.id(), totalAmount)
                )
            )
        );

        // Transition all DRAFT milestones to FUNDED
        for (Milestone m : milestones) {
            if (m.state() == MilestoneState.DRAFT) {
                boolean success = milestoneRepository.transitionState(
                    m.id(),
                    MilestoneState.DRAFT,
                    MilestoneState.FUNDED,
                    m.version()
                );
                if (!success) {
                    throw new InvalidStateTransitionException(
                        MilestoneState.DRAFT,
                        MilestoneState.FUNDED,
                        "Milestone version mismatch or already transitioned"
                    );
                }
            }
        }

        // Emit ProjectFunded event
        outboxWriter.write(
            "Project",
            projectId.toString(),
            "ProjectFunded",
            "escrow.milestone-events",
            new Events.ProjectFunded(projectId, totalAmount)
        );
    }

    /**
     * Submits a milestone: FUNDED -> SUBMITTED.
     * The freelancer submits work; client now has a review window.
     * Simple state transition, no money movement.
     */
    @Transactional(rollbackFor = Exception.class)
    public void submitMilestone(Long milestoneId, String idempotencyKey) {
        Milestone milestone = milestoneRepository.findById(milestoneId)
            .orElseThrow(() -> new IllegalArgumentException("Milestone not found: " + milestoneId));

        if (milestone.state() != MilestoneState.FUNDED) {
            throw InvalidStateTransitionException.invalidTransition(
                milestone.state(),
                MilestoneState.SUBMITTED
            );
        }

        boolean success = milestoneRepository.transitionState(
            milestoneId,
            MilestoneState.FUNDED,
            MilestoneState.SUBMITTED,
            milestone.version()
        );

        if (!success) {
            throw InvalidStateTransitionException.invalidTransition(
                milestone.state(),
                MilestoneState.SUBMITTED
            );
        }

        // Emit MilestoneSubmitted event
        Project project = projectRepository.findById(milestone.projectId())
            .orElseThrow(() -> new IllegalArgumentException("Project not found: " + milestone.projectId()));
        
        int reviewWindowDays = (int) (project.reviewWindow().toDays());
        
        outboxWriter.write(
            "Milestone",
            milestoneId.toString(),
            "MilestoneSubmitted",
            "escrow.milestone-events",
            new Events.MilestoneSubmitted(
                milestoneId,
                milestone.projectId(),
                project.clientId(),
                milestone.amountPaise(),
                reviewWindowDays,
                milestone.title()
            )
        );
    }

    /**
     * Approves a milestone and immediately releases it in the same transaction.
     * SUBMITTED -> APPROVED -> RELEASED
     * Money moves: ESCROW -> FREELANCER_WALLET + PLATFORM_FEES
     *
     * Journal entry:
     *   ESCROW -A
     *   FREELANCER_WALLET +(A - fee)
     *   PLATFORM_FEES +fee
     * where fee = A * feeBps / 10_000 (integer floor division)
     */
    @Transactional(rollbackFor = Exception.class)
    public void approveMilestone(Long milestoneId, String idempotencyKey) {
        Milestone milestone = milestoneRepository.findById(milestoneId)
            .orElseThrow(() -> new IllegalArgumentException("Milestone not found: " + milestoneId));

        if (milestone.state() != MilestoneState.SUBMITTED) {
            throw InvalidStateTransitionException.invalidTransition(
                milestone.state(),
                MilestoneState.APPROVED
            );
        }

        // Calculate fee
        long fee = Money.calculateFee(milestone.amountPaise(), feeBps);
        long freelancerAmount = milestone.amountPaise() - fee;

        // Get accounts
        Project project = projectRepository.findById(milestone.projectId())
            .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        var escrowAccount = accountRepository.findById(project.escrowAccountId())
            .orElseThrow();

        String freelancerWalletCode = "FREELANCER_WALLET:" + project.freelancerId();
        var freelancerWallet = accountRepository.findByCode(freelancerWalletCode)
            .orElseThrow();

        var platformFeesAccount = accountRepository.findByCode("PLATFORM_FEES")
            .orElseThrow();

        // Create 3-leg journal entry: ESCROW -> FREELANCER_WALLET + PLATFORM_FEES
        ledgerService.post(
            new PostingRequest(
                "TRANSFER",
                "MILESTONE",
                milestoneId.toString(),
                idempotencyKey,
                "Release milestone " + milestoneId,
                List.of(
                    new Leg(escrowAccount.id(), -milestone.amountPaise()),
                    new Leg(freelancerWallet.id(), freelancerAmount),
                    new Leg(platformFeesAccount.id(), fee)
                )
            )
        );

        // Transition SUBMITTED -> APPROVED
        boolean approveSuccess = milestoneRepository.transitionState(
            milestoneId,
            MilestoneState.SUBMITTED,
            MilestoneState.APPROVED,
            milestone.version()
        );

        if (!approveSuccess) {
            throw InvalidStateTransitionException.invalidTransition(
                milestone.state(),
                MilestoneState.APPROVED
            );
        }

        // Refresh milestone to get updated version
        Milestone refreshed = milestoneRepository.findById(milestoneId)
            .orElseThrow();

        // Transition APPROVED -> RELEASED
        boolean releaseSuccess = milestoneRepository.transitionState(
            milestoneId,
            MilestoneState.APPROVED,
            MilestoneState.RELEASED,
            refreshed.version()
        );

        if (!releaseSuccess) {
            throw InvalidStateTransitionException.invalidTransition(
                MilestoneState.APPROVED,
                MilestoneState.RELEASED
            );
        }

        // Emit MilestoneApproved event
        outboxWriter.write(
            "Milestone",
            milestoneId.toString(),
            "MilestoneApproved",
            "escrow.milestone-events",
            new Events.MilestoneApproved(
                milestoneId,
                milestone.projectId(),
                milestone.amountPaise()
            )
        );

        // Emit MilestoneReleased event
        outboxWriter.write(
            "Milestone",
            milestoneId.toString(),
            "MilestoneReleased",
            "escrow.milestone-events",
            new Events.MilestoneReleased(
                milestoneId,
                milestone.projectId(),
                project.freelancerId(),
                milestone.amountPaise(),
                fee,
                freelancerAmount,
                false, // not auto-released
                milestone.title()
            )
        );
    }

    /**
     * Cancels a milestone: DRAFT or FUNDED -> CANCELLED -> REFUNDED
     * If FUNDED, refunds to CLIENT_WALLET.
     * A FUNDED milestone can only be cancelled after dueAt + gracePeriod.
     *
     * Money flow (if FUNDED): ESCROW -> CLIENT_WALLET
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelMilestone(Long milestoneId, String idempotencyKey, Duration gracePeriod) {
        Milestone milestone = milestoneRepository.findById(milestoneId)
            .orElseThrow(() -> new IllegalArgumentException("Milestone not found: " + milestoneId));

        if (milestone.state() == MilestoneState.DRAFT) {
            // DRAFT can be cancelled immediately
            boolean success = milestoneRepository.transitionState(
                milestoneId,
                MilestoneState.DRAFT,
                MilestoneState.CANCELLED,
                milestone.version()
            );

            if (!success) {
                throw InvalidStateTransitionException.invalidTransition(
                    MilestoneState.DRAFT,
                    MilestoneState.CANCELLED
                );
            }

            // Emit MilestoneCancelled event
            outboxWriter.write(
                "Milestone",
                milestoneId.toString(),
                "MilestoneCancelled",
                "escrow.milestone-events",
                new Events.MilestoneCancelled(
                    milestoneId,
                    milestone.projectId(),
                    milestone.amountPaise()
                )
            );

            return;
        }

        if (milestone.state() == MilestoneState.FUNDED) {
            // FUNDED can only be cancelled after dueAt + gracePeriod
            Instant canCancelAt = milestone.dueAt().plus(gracePeriod);
            Instant now = clock.instant();

            if (now.isBefore(canCancelAt)) {
                throw new CancelTooEarlyException(milestone.dueAt(), canCancelAt, now);
            }

            // Refund to client
            Project project = projectRepository.findById(milestone.projectId())
                .orElseThrow();

            var escrowAccount = accountRepository.findById(project.escrowAccountId())
                .orElseThrow();

            String clientWalletCode = "CLIENT_WALLET:" + project.clientId();
            var clientWallet = accountRepository.findByCode(clientWalletCode)
                .orElseThrow();

            ledgerService.post(
                new PostingRequest(
                    "TRANSFER",
                    "MILESTONE",
                    milestoneId.toString(),
                    idempotencyKey,
                    "Refund milestone " + milestoneId,
                    List.of(
                        new Leg(escrowAccount.id(), -milestone.amountPaise()),
                        new Leg(clientWallet.id(), milestone.amountPaise())
                    )
                )
            );

            // Transition FUNDED -> CANCELLED
            boolean success = milestoneRepository.transitionState(
                milestoneId,
                MilestoneState.FUNDED,
                MilestoneState.CANCELLED,
                milestone.version()
            );

            if (!success) {
                throw InvalidStateTransitionException.invalidTransition(
                    MilestoneState.FUNDED,
                    MilestoneState.CANCELLED
                );
            }

            // Refresh and transition to REFUNDED
            Milestone refreshed = milestoneRepository.findById(milestoneId)
                .orElseThrow();

            boolean refundSuccess = milestoneRepository.transitionState(
                milestoneId,
                MilestoneState.CANCELLED,
                MilestoneState.REFUNDED,
                refreshed.version()
            );

            if (!refundSuccess) {
                throw InvalidStateTransitionException.invalidTransition(
                    MilestoneState.CANCELLED,
                    MilestoneState.REFUNDED
                );
            }

            // Emit MilestoneCancelled event
            outboxWriter.write(
                "Milestone",
                milestoneId.toString(),
                "MilestoneCancelled",
                "escrow.milestone-events",
                new Events.MilestoneCancelled(
                    milestoneId,
                    milestone.projectId(),
                    milestone.amountPaise()
                )
            );

            // Emit MilestoneRefunded event
            outboxWriter.write(
                "Milestone",
                milestoneId.toString(),
                "MilestoneRefunded",
                "escrow.milestone-events",
                new Events.MilestoneRefunded(
                    milestoneId,
                    milestone.projectId(),
                    project.clientId(),
                    milestone.amountPaise()
                )
            );

            return;
        }

        throw InvalidStateTransitionException.invalidTransition(
            milestone.state(),
            MilestoneState.CANCELLED
        );
    }

    /**
     * Spec for creating a milestone.
     */
    public record MilestoneSpec(String title, long amountPaise, Instant dueAt) {
        public MilestoneSpec {
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("title cannot be blank");
            }
            if (amountPaise <= 0) {
                throw new IllegalArgumentException("amountPaise must be positive");
            }
            if (dueAt == null) {
                throw new IllegalArgumentException("dueAt cannot be null");
            }
        }
    }
}
