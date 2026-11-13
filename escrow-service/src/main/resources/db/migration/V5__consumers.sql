-- P8: Idempotent consumer tracking and audit logging
-- Follows AGENTS.md: consumers safely ignore duplicate deliveries via processed_events deduplication

CREATE TABLE processed_events (
    consumer TEXT NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

COMMENT ON TABLE processed_events IS
    'Deduplication table for Kafka consumers.
     Each consumer group records (consumerName, eventId) after successfully handling an event.
     INSERT ... ON CONFLICT DO NOTHING ensures idempotence: a duplicate delivery finds a row and returns 0 rows inserted.
     Used by IdempotentConsumerSupport.handleOnce() to guarantee each event is processed at most once, despite at-least-once Kafka delivery.';

-- Audit log: append-only record of all domain events
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    aggregate_type TEXT NOT NULL,
    aggregate_id TEXT NOT NULL,
    actor_id BIGINT NULL,
    payload JSONB NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Partial index for querying events by aggregate
CREATE INDEX ix_audit_log_by_aggregate ON audit_log (aggregate_type, aggregate_id);
CREATE INDEX ix_audit_log_by_event_type ON audit_log (event_type);
CREATE INDEX ix_audit_log_by_recorded_at ON audit_log (recorded_at);

-- Immutability trigger: audit_log is append-only
CREATE OR REPLACE FUNCTION raise_immutable_error()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION '% rows are immutable', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_immutable BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION raise_immutable_error();

COMMENT ON TABLE audit_log IS
    'Append-only event log written by AuditLogConsumer (group escrow-audit).
     Records every domain event from escrow.milestone-events, escrow.payment-events, and ledger.entry-events.
     Used for compliance, debugging, and audit trails.';

-- Notifications: messages to be sent to users
CREATE TABLE notifications (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    channel TEXT NOT NULL CHECK (channel = 'EMAIL'),
    template TEXT NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Index for querying notifications by recipient and creation time
CREATE INDEX ix_notifications_by_recipient ON notifications (recipient_user_id, created_at DESC);

COMMENT ON TABLE notifications IS
    'Notification records written by NotificationConsumer (group escrow-notifications).
     Stores messages to be sent to users (clients and freelancers) based on milestone and dispute events.
     Currently no-op (no real email sent), but infrastructure is ready for integration.';

