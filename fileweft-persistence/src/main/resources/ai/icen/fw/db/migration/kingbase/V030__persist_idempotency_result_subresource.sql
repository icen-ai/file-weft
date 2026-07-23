-- Same semantics as the PostgreSQL V030: the persisted first pending task of
-- a submit receipt is a stable framework-generated identifier, never user
-- input. V030 only appends a nullable column and rebuilds the weaker Kingbase
-- result CHECK with the new slot, so 0.3.1 nodes keep rolling against the
-- migrated schema without a write-stop window.
ALTER TABLE fw_idempotency_record
    ADD COLUMN result_subresource_id varchar(256);

ALTER TABLE fw_idempotency_record
    DROP CONSTRAINT ck_fw_idempotency_result;

ALTER TABLE fw_idempotency_record
    ADD CONSTRAINT ck_fw_idempotency_result
        CHECK (
            (
                record_status = 'IN_PROGRESS'
                AND result_resource_type IS NULL
                AND result_resource_id IS NULL
                AND result_related_resource_type IS NULL
                AND result_related_resource_id IS NULL
                AND result_subresource_id IS NULL
                AND completed_time IS NULL
            )
            OR
            (
                record_status = 'COMPLETED'
                AND result_resource_type IS NOT NULL
                AND result_resource_id IS NOT NULL
                AND (
                    (
                        result_related_resource_type IS NULL
                        AND result_related_resource_id IS NULL
                    )
                    OR
                    (
                        result_related_resource_type IS NOT NULL
                        AND result_related_resource_id IS NOT NULL
                    )
                )
                AND completed_time IS NOT NULL
            )
        ) NOT VALID;

ALTER TABLE fw_idempotency_record
    VALIDATE CONSTRAINT ck_fw_idempotency_result;
