-- FileWeft V019 large-table pre-flight for PostgreSQL.
--
-- Run each statement separately through a DBA-approved, autocommit session
-- BEFORE starting the application version that contains Flyway V019. Do not
-- wrap this script in BEGIN/COMMIT: PostgreSQL forbids CREATE INDEX
-- CONCURRENTLY inside a transaction. V019 uses IF NOT EXISTS, so Flyway will
-- safely detect these finished indexes afterwards.

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fw_task_running_lease_expiry
    ON fw_task(lease_expire_time, created_time, id)
    WHERE task_status = 'RUNNING' AND lease_token IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fw_task_legacy_running_updated
    ON fw_task(updated_time, created_time, id)
    WHERE task_status = 'RUNNING' AND lease_token IS NULL;
