-- Escrow domain schema: projects, milestones, and user accounts
-- Follows AGENTS.md account model and money flows

-- Users table: clients and freelancers
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('CLIENT', 'FREELANCER', 'ADMIN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Projects: container for milestones, holds ESCROW and DISPUTE_HOLD accounts
CREATE TABLE IF NOT EXISTS projects (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES users(id),
    freelancer_id BIGINT NOT NULL REFERENCES users(id),
    title TEXT NOT NULL,
    review_window INTERVAL NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    escrow_account_id BIGINT NOT NULL REFERENCES accounts(id),
    dispute_hold_account_id BIGINT NOT NULL REFERENCES accounts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_projects_client ON projects(client_id);
CREATE INDEX idx_projects_freelancer ON projects(freelancer_id);

-- Milestones: units of work within a project
-- States: DRAFT → FUNDED → SUBMITTED → APPROVED → RELEASED (or DISPUTED/CANCELLED)
CREATE TABLE IF NOT EXISTS milestones (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES projects(id),
    seq INT NOT NULL,
    title TEXT NOT NULL,
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    due_at TIMESTAMPTZ NOT NULL,
    state TEXT NOT NULL CHECK (state IN (
        'DRAFT', 'FUNDED', 'SUBMITTED', 'APPROVED', 'RELEASED',
        'DISPUTED', 'RESOLVED', 'CANCELLED', 'REFUNDED'
    )),
    submitted_at TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(project_id, seq)
);

CREATE INDEX idx_milestones_state ON milestones(state);
CREATE INDEX idx_milestones_submitted ON milestones(submitted_at);
CREATE INDEX idx_milestones_project_state ON milestones(project_id, state);

-- Milestone state transitions: audit trail
CREATE TABLE IF NOT EXISTS milestone_transitions (
    id BIGSERIAL PRIMARY KEY,
    milestone_id BIGINT NOT NULL REFERENCES milestones(id),
    from_state TEXT NOT NULL,
    to_state TEXT NOT NULL,
    actor_id BIGINT,
    actor_role TEXT CHECK (actor_role IN ('CLIENT', 'FREELANCER', 'ADMIN', 'SYSTEM')),
    reason TEXT NOT NULL,
    journal_entry_id BIGINT REFERENCES journal_entries(id),
    at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_milestone_transitions_milestone ON milestone_transitions(milestone_id);

-- Triggers to create user wallets when users are created
CREATE OR REPLACE FUNCTION create_user_wallet()
RETURNS TRIGGER AS $$
DECLARE
    wallet_code TEXT;
BEGIN
    IF NEW.role = 'CLIENT' THEN
        wallet_code := 'CLIENT_WALLET:' || NEW.id;
    ELSIF NEW.role = 'FREELANCER' THEN
        wallet_code := 'FREELANCER_WALLET:' || NEW.id;
    ELSE
        RETURN NEW;
    END IF;

    INSERT INTO accounts (code, type, owner_id, allow_negative, balance)
    VALUES (wallet_code, NEW.role || '_WALLET', NEW.id, false, 0)
    ON CONFLICT (code) DO NOTHING;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS create_user_wallet_trigger ON users;
CREATE TRIGGER create_user_wallet_trigger
AFTER INSERT ON users
FOR EACH ROW
EXECUTE FUNCTION create_user_wallet();

-- Trigger to make milestone_transitions immutable
CREATE OR REPLACE FUNCTION prevent_milestone_transition_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'milestone_transitions rows are immutable';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS prevent_milestone_transition_update_trigger ON milestone_transitions;
CREATE TRIGGER prevent_milestone_transition_update_trigger
BEFORE UPDATE OR DELETE ON milestone_transitions
FOR EACH ROW
EXECUTE FUNCTION prevent_milestone_transition_update();
