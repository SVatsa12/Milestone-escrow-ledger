-- Re-running reconciliation for the same date reuses the run (UNIQUE (run_date, entity_type))
-- and used to insert every mismatch again. One row per run, entity and reason.
CREATE UNIQUE INDEX ux_recon_mismatches_run_entity_reason
    ON recon_mismatches (recon_run_id, entity_type, entity_id, mismatch_reason);
