-- Production operators can pre-create these indexes concurrently with the
-- matching script under docs/sql. Keeping this migration transactional lets
-- Flyway retain its schema-history lock; IF NOT EXISTS makes that pre-flight
-- safe and keeps a fresh installation self-contained.
CREATE INDEX IF NOT EXISTS idx_fw_sync_tenant_document_connector_status
    ON fw_sync_record(tenant_id, document_id, connector_name, sync_status, updated_time DESC);

-- Tenant operations screens commonly filter durable work by status. The
-- global eligible-work index remains dedicated to SKIP LOCKED worker claims.
CREATE INDEX IF NOT EXISTS idx_fw_task_tenant_status_updated
    ON fw_task(tenant_id, task_status, updated_time DESC);
