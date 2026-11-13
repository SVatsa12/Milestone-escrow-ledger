-- P9: Razorpay deposits and webhook event tracking
-- Follows AGENTS.md: clients can top up their wallet via Razorpay test-mode
-- Wallet is credited ONLY from verified webhook, never from browser callback

CREATE TABLE deposits (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES users(id),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    razorpay_order_id TEXT UNIQUE NOT NULL,
    razorpay_payment_id TEXT UNIQUE NULL,
    status TEXT NOT NULL CHECK (status IN ('CREATED', 'CAPTURED', 'FAILED', 'AMOUNT_MISMATCH')),
    journal_entry_id BIGINT NULL REFERENCES journal_entries(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Index for querying by client and status
CREATE INDEX ix_deposits_by_client ON deposits (client_id, status);
CREATE INDEX ix_deposits_by_order_id ON deposits (razorpay_order_id);
CREATE INDEX ix_deposits_by_payment_id ON deposits (razorpay_payment_id) WHERE razorpay_payment_id IS NOT NULL;

COMMENT ON TABLE deposits IS
    'Deposit records for client wallet top-ups via Razorpay.
     Status progresses: CREATED → CAPTURED (when webhook succeeds) or FAILED.
     AMOUNT_MISMATCH indicates a webhook amount that didn''t match the order.
     journal_entry_id links the deposit to the ledger entry that credited the wallet.';

-- Webhook event tracking for deduplication
CREATE TABLE webhook_events (
    event_id TEXT PRIMARY KEY,
    source TEXT NOT NULL,
    event_type TEXT NOT NULL,
    signature_valid BOOLEAN NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ NULL,
    payload JSONB NOT NULL
);

-- Index for querying unprocessed events
CREATE INDEX ix_webhook_events_unprocessed ON webhook_events (event_type, processed_at)
    WHERE processed_at IS NULL;

COMMENT ON TABLE webhook_events IS
    'Deduplication and audit log for incoming Razorpay webhooks.
     event_id is the x-razorpay-event-id header (unique per event globally).
     INSERT ... ON CONFLICT DO NOTHING ensures each webhook is processed at most once.
     processed_at tracks when the event was handled.';

