-- Reconciliation against external sources (payout provider, Razorpay).

-- Which external system a mismatch was found against (PAYOUT_PROVIDER, RAZORPAY), and a free-text
-- detail: for MISSING_IN_LEDGER it names our current state (UNKNOWN, FAILED, CANCELLED, absent),
-- which is what tells a harmless lag apart from a real double payout.
ALTER TABLE recon_mismatches ADD COLUMN source VARCHAR(30);
ALTER TABLE recon_mismatches ADD COLUMN detail TEXT;

-- When a deposit was captured, from the application clock. Reconciliation attributes a deposit to
-- the Asia/Kolkata day it was captured; updated_at is DB time and moves on later status changes.
ALTER TABLE deposits ADD COLUMN captured_at TIMESTAMPTZ;
UPDATE deposits SET captured_at = updated_at WHERE status = 'CAPTURED' AND captured_at IS NULL;
CREATE INDEX ix_deposits_captured_at ON deposits (captured_at) WHERE status = 'CAPTURED';

CREATE INDEX ix_payouts_confirmed_at ON payouts (updated_at) WHERE state = 'CONFIRMED';
