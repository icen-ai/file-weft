-- Production pre-flight for V021. Run each statement outside a transaction
-- before deploying the matching Flyway migration when the workflow tables are
-- too large for an ordinary CREATE INDEX. V021 uses the same names with
-- IF NOT EXISTS, so Flyway remains safe after this script succeeds.

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fw_workflow_task_tenant_pending_inbox
    ON fw_workflow_task(tenant_id, created_time DESC, id DESC)
    INCLUDE (workflow_id, assignee_id, updated_time)
    WHERE task_state = 'PENDING';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fw_workflow_instance_tenant_document_history
    ON fw_workflow_instance(tenant_id, document_id, created_time DESC, id DESC)
    INCLUDE (workflow_type, state, updated_time);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fw_workflow_task_tenant_workflow_history
    ON fw_workflow_task(tenant_id, workflow_id, created_time, id)
    INCLUDE (task_state, updated_time);

-- Keep the earlier workflow indexes until query plans have been checked in the
-- target environment. If they are proven redundant, drop them separately with
-- DROP INDEX CONCURRENTLY rather than inside a Flyway transaction.
