-- Seed system accounts for the ledger
-- These are the contra accounts used to track external money flows

INSERT INTO accounts (code, type, owner_id, allow_negative, balance)
VALUES
    ('PAYOUT_IN_FLIGHT', 'PAYOUT_IN_FLIGHT', NULL, false, 0),
    ('PLATFORM_FEES', 'PLATFORM_FEES', NULL, false, 0),
    ('EXTERNAL_BANK:RAZORPAY', 'EXTERNAL_BANK', NULL, true, 0),
    ('EXTERNAL_BANK:PAYOUTS', 'EXTERNAL_BANK', NULL, true, 0)
ON CONFLICT (code) DO NOTHING;
