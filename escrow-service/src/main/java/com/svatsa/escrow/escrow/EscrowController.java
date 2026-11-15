package com.svatsa.escrow.escrow;

import com.svatsa.escrow.common.Actor;
import com.svatsa.escrow.common.Authz;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for escrow operations.
 * Each money-moving endpoint requires an Idempotency-Key header.
 */
@RestController
@RequestMapping("/api/v1")
public class EscrowController {

    private final EscrowService escrowService;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final MilestoneRepository milestoneRepository;

    public EscrowController(
        EscrowService escrowService,
        UserRepository userRepository,
        ProjectRepository projectRepository,
        MilestoneRepository milestoneRepository
    ) {
        this.escrowService = escrowService;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.milestoneRepository = milestoneRepository;
    }

    /**
     * Create a new user (client or freelancer).
     * POST /api/v1/users
     */
    @PostMapping("/users")
    public ResponseEntity<User> createUser(Actor actor, @RequestBody CreateUserRequest request) {
        // ADMIN only: the bootstrap admin (id 1, seeded by V11) creates everyone else, other
        // admins included. A user creates a wallet, and with it an identity the resolver trusts.
        Authz.requireAdmin(actor, "create users");
        if (request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.role() == null || !request.role().matches("CLIENT|FREELANCER|ADMIN")) {
            return ResponseEntity.badRequest().build();
        }

        User user = userRepository.save(request.name(), request.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    /**
     * Create a new project with milestones.
     * POST /api/v1/projects
     */
    @PostMapping("/projects")
    public ResponseEntity<Project> createProject(Actor actor, @RequestBody CreateProjectRequest request) {
        if (request.clientId() == null || request.freelancerId() == null) {
            return ResponseEntity.badRequest().build();
        }
        if (request.title() == null || request.title().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.milestones() == null || request.milestones().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        List<EscrowService.MilestoneSpec> specs = request.milestones()
            .stream()
            .map(m -> new EscrowService.MilestoneSpec(m.title(), m.amountPaise(), m.dueAt()))
            .toList();

        Project project = escrowService.createProject(
            actor,
            request.clientId(),
            request.freelancerId(),
            request.title(),
            request.reviewWindow(),
            specs
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(project);
    }

    /**
     * Fund a project (all milestones transition DRAFT -> FUNDED).
     * POST /api/v1/projects/{projectId}/fund
     */
    @PostMapping("/projects/{projectId}/fund")
    public ResponseEntity<Void> fundProject(
        Actor actor,
        @PathVariable Long projectId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        escrowService.fundProject(actor, projectId, idempotencyKey);
        return ResponseEntity.ok().build();
    }

    /**
     * Submit a milestone (FUNDED -> SUBMITTED).
     * POST /api/v1/milestones/{milestoneId}/submit
     */
    @PostMapping("/milestones/{milestoneId}/submit")
    public ResponseEntity<Void> submitMilestone(
        Actor actor,
        @PathVariable Long milestoneId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        escrowService.submitMilestone(actor, milestoneId, idempotencyKey);
        return ResponseEntity.ok().build();
    }

    /**
     * Approve and release a milestone (SUBMITTED -> APPROVED -> RELEASED).
     * Money is released to freelancer minus fee.
     * POST /api/v1/milestones/{milestoneId}/approve
     */
    @PostMapping("/milestones/{milestoneId}/approve")
    public ResponseEntity<Void> approveMilestone(
        Actor actor,
        @PathVariable Long milestoneId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        escrowService.approveMilestone(actor, milestoneId, idempotencyKey);
        return ResponseEntity.ok().build();
    }

    /**
     * Cancel a milestone and refund if applicable.
     * POST /api/v1/milestones/{milestoneId}/cancel
     */
    @PostMapping("/milestones/{milestoneId}/cancel")
    public ResponseEntity<Void> cancelMilestone(
        Actor actor,
        @PathVariable Long milestoneId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // Grace period: 7 days after due date
        Duration gracePeriod = Duration.ofDays(7);
        escrowService.cancelMilestone(actor, milestoneId, idempotencyKey, gracePeriod);
        return ResponseEntity.ok().build();
    }

    /**
     * Get a project with all its milestones.
     * GET /api/v1/projects/{projectId}
     */
    @GetMapping("/projects/{projectId}")
    public ResponseEntity<ProjectWithMilestones> getProject(@PathVariable Long projectId) {
        Project project = projectRepository.findById(projectId)
            .orElse(null);

        if (project == null) {
            return ResponseEntity.notFound().build();
        }

        List<Milestone> milestones = milestoneRepository.findByProjectId(projectId);
        return ResponseEntity.ok(new ProjectWithMilestones(project, milestones));
    }

    /**
     * Request DTOs
     */
    public record CreateUserRequest(String name, String role) {}

    public record CreateProjectRequest(
        Long clientId,
        Long freelancerId,
        String title,
        Duration reviewWindow,
        List<MilestoneRequest> milestones
    ) {}

    public record MilestoneRequest(String title, long amountPaise, Instant dueAt) {}

    public record ProjectWithMilestones(Project project, List<Milestone> milestones) {}
}
