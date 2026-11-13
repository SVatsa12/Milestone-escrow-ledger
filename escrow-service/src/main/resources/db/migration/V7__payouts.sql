-- P11: Payouts with UNKNOWN state and recovery job
-- Freelancer payouts are a saga: RESERVED → (PROCESSING|UNKNOWN) → (CONFIRMED|FAILED|CANCELLED)

CREATE TABLE payouts (
    id UUID PRIMARY KEY,
    freelancer_id BIGINT NOT NULL,
    wallet_account_id BIGINT NOT NULL,
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    state TEXT NOT NULL CHECK (state IN ('RESERVED', 'PROCESSING', 'UNKNOWN', 'CONFIRMED', 'FAILED', 'CANCELLED')),
    provider_status TEXT,
    attempts INT NOT NULL DEFAULT 0,
    next_check_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INT NOT NULL DEFAULT 0,
    FOREIGN KEY (wallet_account_id) REFERENCES accounts(id)
);

-- Index for recovery job: claim payouts by state and next_check_at
CREATE INDEX idx_payouts_recovery ON payouts (state, next_check_at, id)
    WHERE state IN ('UNKNOWN', 'PROCESSING') OR (state = 'RESERVED' AND updated_at < now() - INTERVAL '30 seconds');

-- Track payout idempotency: same freelancer + idempotency key = same payout
CREATE TABLE payout_idempotency (
    payout_id UUID PRIMARY KEY,
    freelancer_id BIGINT NOT NULL,
    idempotency_key TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(freelancer_id, idempotency_key),
    FOREIGN KEY (payout_id) REFERENCES payouts(id) ON DELETE CASCADE
);

-- Audit: track state transitions
CREATE TABLE payout_transitions (
    id BIGSERIAL PRIMARY KEY,
    payout_id UUID NOT NULL,
    from_state TEXT,
    to_state TEXT NOT NULL,
    reason TEXT,
    actor_id BIGINT,
    actor_role TEXT,
    at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (payout_id) REFERENCES payouts(id) ON DELETE CASCADE
);

CREATE INDEX idx_payout_transitions_payout ON payout_transitions(payout_id, at DESC);
