-- Versioned migrations run exactly once; MySQL 8 CREATE INDEX has no
-- IF NOT EXISTS clause.
CREATE INDEX idx_fw_sync_tenant_document_connector_status
    ON fw_sync_record(tenant_id, document_id, connector_name, sync_status, updated_time DESC);

CREATE INDEX idx_fw_task_tenant_status_updated
    ON fw_task(tenant_id, task_status, updated_time DESC);
