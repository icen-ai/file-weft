-- The V021 inbox index preserves global keyset order, but assignee_id is only
-- an included column there.  This companion index lets PostgreSQL satisfy the
-- two inbox visibility branches (the current assignee and the unassigned
-- pool) without walking pending tasks assigned to other users.
CREATE INDEX IF NOT EXISTS idx_fw_workflow_task_tenant_assignee_pending_inbox
    ON fw_workflow_task(tenant_id, assignee_id, created_time DESC, id DESC)
    INCLUDE (workflow_id, updated_time)
    WHERE task_state = 'PENDING';
