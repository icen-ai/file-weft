ALTER TABLE fw_task
    ADD COLUMN lease_token varchar(128);

-- Existing RUNNING rows have no token and remain in the legacy recovery path.
-- Token-aware workers reclaim them only after their configured legacy cutoff;
-- a rolling upgrade must still drain old workers before shared processing.
-- Production operators can pre-create these indexes concurrently with the
-- matching script under docs/sql. Keeping this migration transactional lets
-- Flyway retain its schema-history lock; IF NOT EXISTS makes that pre-flight
-- safe and keeps a fresh installation self-contained.
CREATE INDEX IF NOT EXISTS idx_fw_task_running_lease_expiry
    ON fw_task(lease_expire_time, created_time, id)
    WHERE task_status = 'RUNNING' AND lease_token IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_fw_task_legacy_running_updated
    ON fw_task(updated_time, created_time, id)
    WHERE task_status = 'RUNNING' AND lease_token IS NULL;
