package com.svatsa.escrow.consumers;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Notification consumer: creates user notifications based on milestone and dispute events.
 *
 * Subscribes to: escrow.milestone-events only (not payment-events or ledger-events)
 *
 * Event → Notification mappings:
 * - MilestoneSubmitted → client: "Work submitted, review within N days or it auto-releases on <date>"
 * - MilestoneReleased → freelancer: "₹X released"
 * - DisputeOpened → both parties (client and freelancer): "Dispute opened for milestone: <title>"
 * - DisputeResolved → both parties: "Dispute resolved, freelancer received ₹X"
 *
 * Deduplicates using IdempotentConsumerSupport.handleOnce() so restarts don't create duplicate notifications.
 * This infrastructure is ready for email integration but currently no-op.
 */
@Service
public class NotificationConsumer {
    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);
    private static final String CONSUMER_NAME = "escrow-notifications";

    private final IdempotentConsumerSupport dedup;
    private final NotificationRepository notificationRepository;

    public NotificationConsumer(IdempotentConsumerSupport dedup, NotificationRepository notificationRepository) {
        this.dedup = dedup;
        this.notificationRepository = notificationRepository;
    }

    /**
     * Listen to escrow.milestone-events and create notifications.
     */
    @KafkaListener(
        topics = {"escrow.milestone-events"},
        groupId = "escrow-notifications"
    )
    @Transactional
    public void handleMilestoneEvent(
        @org.springframework.messaging.handler.annotation.Payload JsonNode payload,
        @org.springframework.messaging.handler.annotation.Header("eventId") String eventIdStr,
        @org.springframework.messaging.handler.annotation.Header("eventType") String eventType
    ) {
        UUID eventId = UUID.fromString(eventIdStr);

        dedup.handleOnce(CONSUMER_NAME, eventId, () -> {
            handleEventInternal(payload, eventType, eventId);
        });
    }

    private void handleEventInternal(
        JsonNode payload,
        String eventType,
        UUID eventId
    ) {
        switch (eventType) {
            case "MilestoneSubmitted" -> handleMilestoneSubmitted(payload, eventId);
            case "MilestoneReleased" -> handleMilestoneReleased(payload, eventId);
            case "DisputeOpened" -> handleDisputeOpened(payload, eventId);
            case "DisputeResolved" -> handleDisputeResolved(payload, eventId);
            default -> log.debug("Ignoring event type: {}", eventType);
        }
    }

    private void handleMilestoneSubmitted(JsonNode payload, UUID eventId) {
        long clientId = payload.get("clientId").asLong();
        long milestoneId = payload.get("milestoneId").asLong();
        int reviewWindowDays = payload.get("reviewWindowDays").asInt();
        String milestoneTitle = payload.get("milestoneTitle").asText();

        // Calculate auto-release date
        Instant submittedAt = Instant.now();
        LocalDate autoReleaseDate = submittedAt
            .atZone(ZoneId.of("Asia/Kolkata"))
            .toLocalDate()
            .plusDays(reviewWindowDays);
        String releaseDateStr = DateTimeFormatter.ISO_LOCAL_DATE.format(autoReleaseDate);

        String body = String.format(
            "Work submitted: \"%s\"\n\n" +
            "Review period: %d days\n" +
            "Auto-release date: %s\n\n" +
            "Approve or auto-release will transfer funds on %s.",
            milestoneTitle, reviewWindowDays, releaseDateStr, releaseDateStr
        );

        notificationRepository.insert(
            eventId, clientId, "EMAIL", "milestone_submitted", body
        );
        log.info("Created notification for MilestoneSubmitted: clientId={}, milestoneId={}, eventId={}",
            clientId, milestoneId, eventId);
    }

    private void handleMilestoneReleased(JsonNode payload, UUID eventId) {
        long freelancerId = payload.get("freelancerId").asLong();
        long amountPaise = payload.get("amountPaise").asLong();
        long feeAmountPaise = payload.get("feeAmountPaise").asLong();
        long freelancerNetPaise = amountPaise - feeAmountPaise;
        String milestoneTitle = payload.get("milestoneTitle").asText();

        double rupees = freelancerNetPaise / 100.0;
        String body = String.format(
            "Milestone released: \"%s\"\n\n" +
            "Amount: ₹%.2f",
            milestoneTitle, rupees
        );

        notificationRepository.insert(
            eventId, freelancerId, "EMAIL", "milestone_released", body
        );
        log.info("Created notification for MilestoneReleased: freelancerId={}, amountPaise={}, eventId={}",
            freelancerId, freelancerNetPaise, eventId);
    }

    private void handleDisputeOpened(JsonNode payload, UUID eventId) {
        long clientId = payload.get("clientId").asLong();
        long freelancerId = payload.get("freelancerId").asLong();
        String milestoneTitle = payload.get("milestoneTitle").asText();
        String reason = payload.get("reason").asText();

        String body = String.format(
            "Dispute opened for milestone: \"%s\"\n\n" +
            "Reason: %s\n\n" +
            "An admin will review and resolve this dispute.",
            milestoneTitle, reason
        );

        // Notify both parties
        notificationRepository.insert(
            eventId, clientId, "EMAIL", "dispute_opened", body
        );
        notificationRepository.insert(
            eventId, freelancerId, "EMAIL", "dispute_opened", body
        );
        log.info("Created dispute notifications: clientId={}, freelancerId={}, eventId={}",
            clientId, freelancerId, eventId);
    }

    private void handleDisputeResolved(JsonNode payload, UUID eventId) {
        long clientId = payload.get("clientId").asLong();
        long freelancerId = payload.get("freelancerId").asLong();
        long freelancerSharePaise = payload.get("freelancerSharePaise").asLong();
        long clientSharePaise = payload.get("clientSharePaise").asLong();
        long feeAmountPaise = payload.get("feeAmountPaise").asLong();
        String milestoneTitle = payload.get("milestoneTitle").asText();

        double freelancerRupees = (freelancerSharePaise - feeAmountPaise) / 100.0;
        double clientRupees = clientSharePaise / 100.0;

        // Freelancer notification
        String freelancerBody = String.format(
            "Dispute resolved for milestone: \"%s\"\n\n" +
            "You received: ₹%.2f",
            milestoneTitle, freelancerRupees
        );
        notificationRepository.insert(
            eventId, freelancerId, "EMAIL", "dispute_resolved", freelancerBody
        );

        // Client notification
        String clientBody = String.format(
            "Dispute resolved for milestone: \"%s\"\n\n" +
            "Refunded to you: ₹%.2f",
            milestoneTitle, clientRupees
        );
        notificationRepository.insert(
            eventId, clientId, "EMAIL", "dispute_resolved", clientBody
        );

        log.info("Created dispute resolution notifications: clientId={}, freelancerId={}, eventId={}",
            clientId, freelancerId, eventId);
    }
}
