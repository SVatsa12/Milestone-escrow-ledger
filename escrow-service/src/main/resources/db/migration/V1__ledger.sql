-- Ledger schema with double-entry constraints
-- All money movements are recorded as balanced journal entries
-- with immutable ledger rows enforced by triggers

CREATE TABLE accounts (
    id BIGSERIAL PRIMARY KEY,
    code TEXT UNIQUE NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('CLIENT_WALLET', 'FREELANCER_WALLET', 'ESCROW', 'DISPUTE_HOLD', 'PAYOUT_IN_FLIGHT', 'PLATFORM_FEES', 'EXTERNAL_BANK')),
    owner_id BIGINT NULL,
    allow_negative BOOLEAN NOT NULL DEFAULT false,
    balance BIGINT NOT NULL DEFAULT 0 CHECK (allow_negative OR balance >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_accounts_code ON accounts(code);
CREATE INDEX idx_accounts_type ON accounts(type);

-- Journal entries are immutable records of transactions
CREATE TABLE journal_entries (
    id BIGSERIAL PRIMARY KEY,
    type TEXT NOT NULL,
    reference_type TEXT,
    reference_id TEXT,
    idempotency_key TEXT UNIQUE NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_journal_entries_idempotency_key ON journal_entries(idempotency_key);
CREATE INDEX idx_journal_entries_reference ON journal_entries(reference_type, reference_id);
CREATE INDEX idx_journal_entries_type ON journal_entries(type);

-- Postings are the debit/credit pairs within a journal entry
-- A posting with amount > 0 is a credit (increase), amount < 0 is a debit (decrease)
CREATE TABLE postings (
    id BIGSERIAL PRIMARY KEY,
    entry_id BIGINT NOT NULL REFERENCES journal_entries(id),
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    amount BIGINT NOT NULL CHECK (amount <> 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_postings_entry_id ON postings(entry_id);
CREATE INDEX idx_postings_account_id_id ON postings(account_id, id);

-- Idempotency keys for HTTP-level idempotency
-- Stores the cached response for a completed request
CREATE TABLE idempotency_keys (
    key TEXT PRIMARY KEY,
    scope TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    response_status INT NOT NULL,
    response_body JSONB NOT NULL,
    journal_entry_id BIGINT NULL REFERENCES journal_entries(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_idempotency_keys_scope_created ON idempotency_keys(scope, created_at);

-- ============================================================================
-- DB-level Invariant Guards
-- ============================================================================

-- Invariant 1: All journal entries must sum to zero and have >= 2 postings
-- Implemented as a DEFERRABLE INITIALLY DEFERRED constraint trigger
-- so it fires at transaction commit after all postings are inserted
CREATE OR REPLACE FUNCTION check_entry_balanced()
RETURNS TRIGGER AS $$
DECLARE
    entry_sum BIGINT;
    posting_count INT;
BEGIN
    -- Check that all postings for this entry sum to 0
    SELECT COALESCE(SUM(amount), 0), COUNT(*)
    INTO entry_sum, posting_count
    FROM postings
    WHERE entry_id = NEW.entry_id;

    IF entry_sum <> 0 THEN
        RAISE EXCEPTION 'Journal entry % does not balance: sum = %', NEW.entry_id, entry_sum
        USING ERRCODE = 'check_violation';
    END IF;

    IF posting_count < 2 THEN
        RAISE EXCEPTION 'Journal entry % has only % posting(s), need >= 2', NEW.entry_id, posting_count
        USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger fires after each posting insert, but constraint is deferred
-- so it only checks when the transaction commits
CREATE CONSTRAINT TRIGGER postings_balanced
AFTER INSERT ON postings
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION check_entry_balanced();

-- Invariant 2: Ledger rows are immutable (no UPDATE, DELETE)
CREATE OR REPLACE FUNCTION prevent_journal_entry_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Ledger rows are immutable: cannot modify journal_entry %', NEW.id
    USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER journal_entries_immutable_update
BEFORE UPDATE ON journal_entries
FOR EACH ROW
EXECUTE FUNCTION prevent_journal_entry_mutation();

CREATE TRIGGER journal_entries_immutable_delete
BEFORE DELETE ON journal_entries
FOR EACH ROW
EXECUTE FUNCTION prevent_journal_entry_mutation();

-- Invariant 2b: Postings are also immutable
CREATE OR REPLACE FUNCTION prevent_posting_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Ledger rows are immutable: cannot modify posting %', NEW.id
    USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER postings_immutable_update
BEFORE UPDATE ON postings
FOR EACH ROW
EXECUTE FUNCTION prevent_posting_mutation();

CREATE TRIGGER postings_immutable_delete
BEFORE DELETE ON postings
FOR EACH ROW
EXECUTE FUNCTION prevent_posting_mutation();

-- ============================================================================
-- System Accounts (seeded by V2 migration)
-- ============================================================================
-- These accounts are created in V2__ledger_seed.sql to keep schema separate from seed data
-- - PAYOUT_IN_FLIGHT: tracks freelancer payments awaiting settlement (allow_negative=false)
-- - PLATFORM_FEES: accumulates service fees (allow_negative=false)
-- - EXTERNAL_BANK:RAZORPAY: funding source from Razorpay (allow_negative=true)
-- - EXTERNAL_BANK:PAYOUTS: payout destination (allow_negative=true)
