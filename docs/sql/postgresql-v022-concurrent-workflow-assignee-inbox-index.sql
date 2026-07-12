-- Production pre-flight for V022. Run this statement outside a transaction
-- before deploying the matching Flyway migration when fw_workflow_task is too
-- large for an ordinary CREATE INDEX. V022 uses the same name with
-- IF NOT EXISTS, so Flyway remains safe after this statement succeeds.

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fw_workflow_task_tenant_assignee_pending_inbox
    ON fw_workflow_task(tenant_id, assignee_id, created_time DESC, id DESC)
    INCLUDE (workflow_id, updated_time)
    WHERE task_state = 'PENDING';

-- Retain the V021 global-order inbox index until the target environment has
-- compared real query plans and rollback requirements. Remove either index
-- only in a separate DBA change using DROP INDEX CONCURRENTLY.
