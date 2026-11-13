-- P7: Transactional outbox for reliable event delivery
-- Follows AGENTS.md invariant 11: events written in same transaction as state changes

CREATE TABLE outbox (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID UNIQUE NOT NULL,
    aggregate_type TEXT NOT NULL,
    aggregate_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    topic TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT NULL
);

-- Partial index for efficient polling of unpublished events
CREATE INDEX ix_outbox_unpublished ON outbox (id)
    WHERE published_at IS NULL;

-- Index for publishing order and deduplication
CREATE INDEX ix_outbox_by_event_id ON outbox (event_id);

-- Index for querying by aggregate (e.g., all events for a project)
CREATE INDEX ix_outbox_by_aggregate ON outbox (aggregate_type, aggregate_id);

-- Enforce that event_id is unique (business-level idempotency)
-- This is already ensured by the UNIQUE constraint above
COMMENT ON TABLE outbox IS
    'Transactional outbox for publishing domain events to Kafka.
     Every event is written in the same transaction as its state change.
     OutboxRelay polls this table and publishes to Kafka with at-least-once delivery.
     Consumers must deduplicate by event_id.';
