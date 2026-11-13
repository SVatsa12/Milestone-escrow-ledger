-- Reconciliation runs: daily checks against external system state
CREATE TABLE recon_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_date DATE NOT NULL,
    entity_type VARCHAR(50) NOT NULL, -- 'PAYOUT' or 'DEPOSIT'
    started_at TIMESTAMP NOT NULL DEFAULT now(),
    completed_at TIMESTAMP,
    status VARCHAR(50) NOT NULL DEFAULT 'RUNNING', -- RUNNING, COMPLETED, FAILED
    mismatch_count INT DEFAULT 0,
    notes TEXT,
    UNIQUE (run_date, entity_type)
);

CREATE INDEX idx_recon_runs_date ON recon_runs(run_date DESC);
CREATE INDEX idx_recon_runs_status ON recon_runs(status);

-- Reconciliation mismatches: discrepancies found during a recon run
CREATE TABLE recon_mismatches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recon_run_id UUID NOT NULL REFERENCES recon_runs(id) ON DELETE CASCADE,
    entity_id TEXT NOT NULL, -- payout UUID or deposit ID
    entity_type VARCHAR(50) NOT NULL, -- 'PAYOUT' or 'DEPOSIT'
    expected_state VARCHAR(50), -- what we think the state should be
    actual_state VARCHAR(50), -- what the provider says
    expected_amount_paise BIGINT,
    actual_amount_paise BIGINT,
    mismatch_reason VARCHAR(255), -- e.g., 'STATE_MISMATCH', 'AMOUNT_MISMATCH', 'NOT_FOUND'
    severity VARCHAR(20) NOT NULL DEFAULT 'ERROR', -- ERROR, WARN
    action_taken VARCHAR(255), -- e.g., 'MARKED_FOR_MANUAL_REVIEW', 'AUTO_REVERSED'
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_recon_mismatches_run ON recon_mismatches(recon_run_id);
CREATE INDEX idx_recon_mismatches_entity ON recon_mismatches(entity_type, entity_id);
CREATE INDEX idx_recon_mismatches_severity ON recon_mismatches(severity);
