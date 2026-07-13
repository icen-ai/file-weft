-- MySQL 8 supports CREATE INDEX IF NOT EXISTS from 8.0.13 onward.
CREATE INDEX IF NOT EXISTS idx_fw_sync_tenant_document_connector_status
    ON fw_sync_record(tenant_id, document_id, connector_name, sync_status, updated_time DESC);

CREATE INDEX IF NOT EXISTS idx_fw_task_tenant_status_updated
    ON fw_task(tenant_id, task_status, updated_time DESC);
