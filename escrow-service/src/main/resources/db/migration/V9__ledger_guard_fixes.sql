-- Ledger guard fixes found when LedgerSchemaIT first ran against Postgres.

-- 1. A journal entry with NO postings used to commit: the balancing trigger fires only on
--    postings inserts, so an entry nobody posted to was never checked. Check every new entry
--    at commit as well, with the same rule (sum = 0 and >= 2 postings).
CREATE OR REPLACE FUNCTION check_new_entry_has_postings()
RETURNS TRIGGER AS $$
DECLARE
    entry_sum BIGINT;
    posting_count INT;
BEGIN
    SELECT COALESCE(SUM(amount), 0), COUNT(*)
    INTO entry_sum, posting_count
    FROM postings
    WHERE entry_id = NEW.id;

    -- Same order and messages as V1's postings trigger: balance first, then posting count.
    IF entry_sum <> 0 THEN
        RAISE EXCEPTION 'Journal entry % does not balance: sum = %', NEW.id, entry_sum
        USING ERRCODE = 'check_violation';
    END IF;

    IF posting_count < 2 THEN
        RAISE EXCEPTION 'Journal entry % has only % posting(s), need >= 2', NEW.id, posting_count
        USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER journal_entries_have_postings
AFTER INSERT ON journal_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION check_new_entry_has_postings();
