-- MySQL 8 does not support INCLUDE clauses. The included columns are added as
-- ordinary trailing key columns; the resulting index still covers the same
-- query patterns at the cost of slightly larger keys.
CREATE INDEX idx_fw_workflow_task_tenant_pending_inbox
    ON fw_workflow_task(tenant_id, created_time DESC, id DESC, workflow_id, assignee_id, updated_time);

CREATE INDEX idx_fw_workflow_instance_tenant_document_history
    ON fw_workflow_instance(tenant_id, document_id, created_time DESC, id DESC, workflow_type, state, updated_time);

CREATE INDEX idx_fw_workflow_task_tenant_workflow_history
    ON fw_workflow_task(tenant_id, workflow_id, created_time, id, task_state, updated_time);
