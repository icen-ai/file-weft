-- FileWeft V016 large-table pre-flight for PostgreSQL.
--
-- Run each statement separately through a DBA-approved, autocommit session
-- BEFORE starting the application version that contains Flyway V016. Do not
-- wrap this script in BEGIN/COMMIT: PostgreSQL forbids CREATE INDEX
-- CONCURRENTLY inside a transaction. V016 uses IF NOT EXISTS, so Flyway will
-- safely detect these finished indexes afterwards.

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fw_sync_tenant_document_connector_status
    ON fw_sync_record(tenant_id, document_id, connector_name, sync_status, updated_time DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fw_task_tenant_status_updated
    ON fw_task(tenant_id, task_status, updated_time DESC);

-- The former idx_fw_sync_tenant_document_connector index is intentionally not
-- removed by V016. It can be dropped CONCURRENTLY only after a DBA has checked
-- production query plans and rollback requirements:
-- DROP INDEX CONCURRENTLY IF EXISTS idx_fw_sync_tenant_document_connector;
