-- Bootstrap ADMIN and immutable user roles.
--
-- Auth is a stub (X-Actor-Id / X-Actor-Role headers), but ActorArgumentResolver now checks both
-- against this table: the id must exist and the claimed role must be the stored one. Creating
-- users is ADMIN-only, so one ADMIN has to exist from the start:
--
--   id 1, name 'bootstrap-admin', role ADMIN
--
-- Send `X-Actor-Id: 1` and `X-Actor-Role: ADMIN` to act as it (README, AGENTS.md).

INSERT INTO users (id, name, role) VALUES (1, 'bootstrap-admin', 'ADMIN')
ON CONFLICT (id) DO NOTHING;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM users WHERE id = 1 AND role = 'ADMIN') THEN
        RAISE EXCEPTION 'V11: user 1 already exists and is not an ADMIN. The bootstrap admin needs id 1: '
            'recreate the database (docker compose down -v) before migrating.';
    END IF;
END $$;

-- The explicit id did not advance the sequence; without this the next user would collide with 1.
SELECT setval(pg_get_serial_sequence('users', 'id'), GREATEST((SELECT max(id) FROM users), 1));

-- A user's id and role never change, and users are never deleted. The resolver caches
-- id -> role, and this is what makes that cache correct.
CREATE OR REPLACE FUNCTION prevent_user_identity_change()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'users rows cannot be deleted (id %)', OLD.id;
    END IF;
    IF NEW.id <> OLD.id OR NEW.role <> OLD.role THEN
        RAISE EXCEPTION 'a user''s id and role are immutable (id %)', OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS prevent_user_identity_change_trigger ON users;
CREATE TRIGGER prevent_user_identity_change_trigger
BEFORE UPDATE OR DELETE ON users
FOR EACH ROW
EXECUTE FUNCTION prevent_user_identity_change();
