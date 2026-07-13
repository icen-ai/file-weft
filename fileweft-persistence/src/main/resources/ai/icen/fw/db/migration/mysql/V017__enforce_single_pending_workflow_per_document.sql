-- MySQL 8.0.13+ supports functional/partial indexes, but partial unique indexes
-- with a simple predicate are not as widely used as in PostgreSQL. We express
-- the intent with a non-unique filtered index when supported, otherwise fall back
-- to a plain composite index. The application layer enforces the single PENDING
-- workflow invariant for MySQL.
CREATE INDEX IF NOT EXISTS uq_fw_workflow_instance_tenant_document_pending
    ON fw_workflow_instance(tenant_id, document_id, state);
