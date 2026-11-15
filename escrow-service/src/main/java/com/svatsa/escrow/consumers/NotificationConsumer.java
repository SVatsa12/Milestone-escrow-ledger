package com.svatsa.escrow.consumers;

import com.fasterxml.jackson.databind.JsonNode;
import com.svatsa.escrow.common.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
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
    private final Clock clock;

    public NotificationConsumer(IdempotentConsumerSupport dedup, NotificationRepository notificationRepository, Clock clock) {
        this.dedup = dedup;
        this.notificationRepository = notificationRepository;
        this.clock = clock;
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
        JsonNode envelope,
        String eventType,
        UUID eventId
    ) {
        // Kafka carries the relay's EventEnvelope; the event's own fields are under "payload",
        // in snake_case (see outbox.events.Events). This consumer used to read camelCase names at
        // the envelope's top level, so every handler threw NPE and no notification was ever sent.
        JsonNode payload = envelope.path("payload").isObject() ? envelope.get("payload") : envelope;
        switch (eventType) {
            case "MilestoneSubmitted" -> handleMilestoneSubmitted(payload, eventId);
            case "MilestoneReleased" -> handleMilestoneReleased(payload, eventId);
            case "DisputeOpened" -> handleDisputeOpened(payload, eventId);
            case "DisputeResolved" -> handleDisputeResolved(payload, eventId);
            default -> log.debug("Ignoring event type: {}", eventType);
        }
    }

    private void handleMilestoneSubmitted(JsonNode payload, UUID eventId) {
        long clientId = field(payload, "client_id").asLong();
        long milestoneId = field(payload, "milestone_id").asLong();
        int reviewWindowDays = field(payload, "review_window_days").asInt();
        String milestoneTitle = field(payload, "milestone_title").asText();

        // Calculate auto-release date
        Instant submittedAt = clock.instant();
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
        long freelancerId = field(payload, "freelancer_id").asLong();
        long amountPaise = field(payload, "amount_paise").asLong();
        long feeAmountPaise = field(payload, "fee_amount_paise").asLong();
        long freelancerNetPaise = amountPaise - feeAmountPaise;
        String milestoneTitle = field(payload, "milestone_title").asText();

        String body = String.format(
            "Milestone released: \"%s\"\n\n" +
            "Amount: %s",
            milestoneTitle, Money.formatPaise(freelancerNetPaise)
        );

        notificationRepository.insert(
            eventId, freelancerId, "EMAIL", "milestone_released", body
        );
        log.info("Created notification for MilestoneReleased: freelancerId={}, amountPaise={}, eventId={}",
            freelancerId, freelancerNetPaise, eventId);
    }

    private void handleDisputeOpened(JsonNode payload, UUID eventId) {
        long clientId = field(payload, "client_id").asLong();
        long freelancerId = field(payload, "freelancer_id").asLong();
        String milestoneTitle = field(payload, "milestone_title").asText();
        String reason = field(payload, "reason").asText();

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
        long clientId = field(payload, "client_id").asLong();
        long freelancerId = field(payload, "freelancer_id").asLong();
        long freelancerSharePaise = field(payload, "freelancer_share_paise").asLong();
        long clientSharePaise = field(payload, "client_share_paise").asLong();
        long feeAmountPaise = field(payload, "fee_amount_paise").asLong();
        String milestoneTitle = field(payload, "milestone_title").asText();

        // Freelancer notification
        String freelancerBody = String.format(
            "Dispute resolved for milestone: \"%s\"\n\n" +
            "You received: %s",
            milestoneTitle, Money.formatPaise(freelancerSharePaise - feeAmountPaise)
        );
        notificationRepository.insert(
            eventId, freelancerId, "EMAIL", "dispute_resolved", freelancerBody
        );

        // Client notification
        String clientBody = String.format(
            "Dispute resolved for milestone: \"%s\"\n\n" +
            "Refunded to you: %s",
            milestoneTitle, Money.formatPaise(clientSharePaise)
        );
        notificationRepository.insert(
            eventId, clientId, "EMAIL", "dispute_resolved", clientBody
        );

        log.info("Created dispute resolution notifications: clientId={}, freelancerId={}, eventId={}",
            clientId, freelancerId, eventId);
    }

    private static JsonNode field(JsonNode payload, String name) {
        JsonNode value = payload.get(name);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("event payload is missing field '" + name + "'");
        }
        return value;
    }
}
