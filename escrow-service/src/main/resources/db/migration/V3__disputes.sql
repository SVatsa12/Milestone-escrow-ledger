-- Disputes: freeze funds during conflicts, resolve with split between parties
-- Follows AGENTS.md account model and money flows

CREATE TABLE IF NOT EXISTS disputes (
    id BIGSERIAL PRIMARY KEY,
    milestone_id BIGINT NOT NULL UNIQUE REFERENCES milestones(id),
    opened_by BIGINT NOT NULL REFERENCES users(id),
    opened_by_role TEXT NOT NULL CHECK (opened_by_role IN ('CLIENT', 'FREELANCER')),
    reason TEXT NOT NULL,
    evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    freelancer_share_paise BIGINT,
    client_share_paise BIGINT,
    resolution_note TEXT,
    resolved_by BIGINT REFERENCES users(id),
    journal_entry_id BIGINT REFERENCES journal_entries(id),
    opened_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);

CREATE INDEX idx_disputes_milestone ON disputes(milestone_id);
CREATE INDEX idx_disputes_status ON disputes(status);
CREATE INDEX idx_disputes_opened_at ON disputes(opened_at);

-- Trigger to make disputes immutable once resolved
CREATE OR REPLACE FUNCTION prevent_resolved_dispute_update()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 'RESOLVED' THEN
        RAISE EXCEPTION 'Cannot update a resolved dispute';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS prevent_resolved_dispute_update_trigger ON disputes;
CREATE TRIGGER prevent_resolved_dispute_update_trigger
BEFORE UPDATE ON disputes
FOR EACH ROW
EXECUTE FUNCTION prevent_resolved_dispute_update();

-- Trigger to prevent deletion of disputes
CREATE OR REPLACE FUNCTION prevent_dispute_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Disputes are immutable and cannot be deleted';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS prevent_dispute_delete_trigger ON disputes;
CREATE TRIGGER prevent_dispute_delete_trigger
BEFORE DELETE ON disputes
FOR EACH ROW
EXECUTE FUNCTION prevent_dispute_delete();
