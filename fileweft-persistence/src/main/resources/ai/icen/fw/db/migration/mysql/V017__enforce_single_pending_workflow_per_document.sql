-- MySQL has no PostgreSQL-style partial unique index. Nullable generated
-- columns provide the same database-level invariant without collapsing the
-- tenant and document identifiers into a lossy concatenated key: only PENDING
-- rows produce non-null values, while every historical state remains NULL and
-- can therefore occur more than once.
ALTER TABLE fw_workflow_instance
    ADD COLUMN pending_tenant_id varchar(64)
        GENERATED ALWAYS AS (
            CASE WHEN state = 'PENDING' THEN tenant_id ELSE NULL END
        ) STORED,
    ADD COLUMN pending_document_id varchar(64)
        GENERATED ALWAYS AS (
            CASE WHEN state = 'PENDING' THEN document_id ELSE NULL END
        ) STORED,
    ADD UNIQUE INDEX uq_fw_workflow_instance_tenant_document_pending(
        pending_tenant_id,
        pending_document_id
    );
