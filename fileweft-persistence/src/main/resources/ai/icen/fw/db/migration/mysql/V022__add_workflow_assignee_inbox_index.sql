-- INCLUDE columns from the PostgreSQL original are appended as ordinary key
-- columns because MySQL does not support INCLUDE.
CREATE INDEX idx_fw_workflow_task_tenant_assignee_pending_inbox
    ON fw_workflow_task(tenant_id, assignee_id, created_time DESC, id DESC, workflow_id, updated_time);
