-- The provider's own ledger of payouts, in schema "provider" (Flyway schemas=provider).
-- One row per idempotency key, ever: the unique index is what makes concurrent POSTs and
-- cancels with the same key race-safe.
CREATE TABLE provider.payouts (
    id               UUID PRIMARY KEY,
    idempotency_key  TEXT NOT NULL UNIQUE,
    beneficiary      TEXT,                                   -- NULL for a cancel tombstone
    amount_paise     BIGINT CHECK (amount_paise > 0),        -- NULL for a cancel tombstone
    status           TEXT NOT NULL CHECK (status IN ('PROCESSING', 'SUCCESS', 'FAILED', 'CANCELLED')),
    failure_reason   TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    settled_at       TIMESTAMPTZ,
    -- ASYNC mode only: when the scheduler settles a PROCESSING payout, and to what. Both are
    -- chosen (from the seeded RNG) when the payout is accepted, so runs are reproducible.
    async_due_at     TIMESTAMPTZ,
    async_outcome    TEXT CHECK (async_outcome IN ('SUCCESS', 'FAILED'))
);

CREATE INDEX ix_provider_payouts_async_due ON provider.payouts (async_due_at) WHERE status = 'PROCESSING';
CREATE INDEX ix_provider_payouts_settled ON provider.payouts (settled_at) WHERE status = 'SUCCESS';
