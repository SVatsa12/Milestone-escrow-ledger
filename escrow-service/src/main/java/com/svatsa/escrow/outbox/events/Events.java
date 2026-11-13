package com.svatsa.escrow.outbox.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Domain events for the escrow system.
 * All events are emitted to Kafka topics via the outbox pattern.
 */
public final class Events {

    private Events() {
    }

    // ============ Milestone Events (escrow.milestone-events) ============

    public record ProjectFunded(
        @JsonProperty("project_id") Long projectId,
        @JsonProperty("total_amount_paise") Long totalAmountPaise
    ) {
    }

    public record MilestoneSubmitted(
        @JsonProperty("milestone_id") Long milestoneId,
        @JsonProperty("project_id") Long projectId,
        @JsonProperty("client_id") Long clientId,
        @JsonProperty("amount_paise") Long amountPaise,
        @JsonProperty("review_window_days") Integer reviewWindowDays,
        @JsonProperty("milestone_title") String milestoneTitle
    ) {
    }

    public record MilestoneApproved(
        @JsonProperty("milestone_id") Long milestoneId,
        @JsonProperty("project_id") Long projectId,
        @JsonProperty("amount_paise") Long amountPaise
    ) {
    }

    public record MilestoneReleased(
        @JsonProperty("milestone_id") Long milestoneId,
        @JsonProperty("project_id") Long projectId,
        @JsonProperty("freelancer_id") Long freelancerId,
        @JsonProperty("amount_paise") Long amountPaise,
        @JsonProperty("fee_amount_paise") Long feeAmountPaise,
        @JsonProperty("freelancer_net_paise") Long freelancerNetPaise,
        @JsonProperty("auto_released") Boolean autoReleased,
        @JsonProperty("milestone_title") String milestoneTitle
    ) {
    }

    public record MilestoneCancelled(
        @JsonProperty("milestone_id") Long milestoneId,
        @JsonProperty("project_id") Long projectId,
        @JsonProperty("amount_paise") Long amountPaise
    ) {
    }

    public record MilestoneRefunded(
        @JsonProperty("milestone_id") Long milestoneId,
        @JsonProperty("project_id") Long projectId,
        @JsonProperty("client_id") Long clientId,
        @JsonProperty("amount_paise") Long amountPaise
    ) {
    }

    public record DisputeOpened(
        @JsonProperty("dispute_id") Long disputeId,
        @JsonProperty("milestone_id") Long milestoneId,
        @JsonProperty("project_id") Long projectId,
        @JsonProperty("client_id") Long clientId,
        @JsonProperty("freelancer_id") Long freelancerId,
        @JsonProperty("opened_by") Long openedBy,
        @JsonProperty("opened_by_role") String openedByRole,
        @JsonProperty("reason") String reason,
        @JsonProperty("milestone_title") String milestoneTitle
    ) {
    }

    public record DisputeResolved(
        @JsonProperty("dispute_id") Long disputeId,
        @JsonProperty("milestone_id") Long milestoneId,
        @JsonProperty("project_id") Long projectId,
        @JsonProperty("client_id") Long clientId,
        @JsonProperty("freelancer_id") Long freelancerId,
        @JsonProperty("freelancer_share_paise") Long freelancerSharePaise,
        @JsonProperty("client_share_paise") Long clientSharePaise,
        @JsonProperty("fee_amount_paise") Long feeAmountPaise,
        @JsonProperty("milestone_title") String milestoneTitle
    ) {
    }

    // ============ Payment Events (escrow.payment-events) ============

    public record DepositCaptured(
        @JsonProperty("deposit_id") Long depositId,
        @JsonProperty("client_id") Long clientId,
        @JsonProperty("amount_paise") Long amountPaise,
        @JsonProperty("payment_id") String paymentId,
        @JsonProperty("order_id") String orderId,
        @JsonProperty("entry_id") Long entryId
    ) {
    }

    public record LedgerEntryPosted(
        @JsonProperty("entry_id") Long entryId,
        @JsonProperty("entry_type") String entryType,
        @JsonProperty("reference_type") String referenceType,
        @JsonProperty("reference_id") String referenceId,
        @JsonProperty("legs") List<LegEvent> legs
    ) {
        public record LegEvent(
            @JsonProperty("account_code") String accountCode,
            @JsonProperty("amount_paise") Long amountPaise
        ) {
        }
    }
}
