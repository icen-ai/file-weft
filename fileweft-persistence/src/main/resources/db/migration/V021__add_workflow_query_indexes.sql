-- Formal workflow inboxes page pending tasks by (created_time, id) after
-- applying tenant, assignment, active-workflow, document, and folder filters.
CREATE INDEX IF NOT EXISTS idx_fw_workflow_task_tenant_pending_inbox
    ON fw_workflow_task(tenant_id, created_time DESC, id DESC)
    INCLUDE (workflow_id, assignee_id, updated_time)
    WHERE task_state = 'PENDING';

-- Document workflow history uses the same deterministic keyset order. Include
-- the public projection fields so a bounded history page needs fewer heap reads.
CREATE INDEX IF NOT EXISTS idx_fw_workflow_instance_tenant_document_history
    ON fw_workflow_instance(tenant_id, document_id, created_time DESC, id DESC)
    INCLUDE (workflow_type, state, updated_time);

-- The history query loads every task for only the bounded workflow page and
-- orders those tasks deterministically inside each workflow.
CREATE INDEX IF NOT EXISTS idx_fw_workflow_task_tenant_workflow_history
    ON fw_workflow_task(tenant_id, workflow_id, created_time, id)
    INCLUDE (task_state, updated_time);
