-- The V021 inbox index preserves global keyset order, but assignee_id is only
-- an appended key column there. This companion index covers the same two inbox
-- visibility branches without walking pending tasks assigned to other users.
-- Kingbase V8R6 does not support INCLUDE clauses, so included columns are
-- appended as ordinary key columns.
CREATE INDEX IF NOT EXISTS idx_fw_workflow_task_tenant_assignee_pending_inbox
    ON fw_workflow_task(tenant_id, assignee_id, created_time DESC, id DESC, workflow_id, updated_time)
    WHERE task_state = 'PENDING';
