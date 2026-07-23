-- The first pending task of a submit receipt is a stable framework-generated
-- identifier. Persisting it in the idempotency result lets a same-key replay
-- return the exact fresh receipt instead of degrading the task id to null.
-- This column never carries user input.
--
-- V030 only appends a nullable column and rebuilds the result CHECK with an
-- equivalent widened definition, so 0.3.1 nodes keep rolling against the
-- migrated schema: they never read or write result_subresource_id, and every
-- row they produce still satisfies the new constraint. No write-stop window
-- is required.
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
                AND result_resource_type ~ '^[A-Za-z][A-Za-z0-9._:-]{0,127}$'
                AND result_resource_id IS NOT NULL
                AND btrim(result_resource_id) <> ''
                AND result_resource_id !~ '[[:cntrl:]]'
                AND result_resource_id !~ '^[[:space:]]|[[:space:]]$'
                AND (
                    (
                        result_related_resource_type IS NULL
                        AND result_related_resource_id IS NULL
                    )
                    OR
                    (
                        result_related_resource_type IS NOT NULL
                        AND result_related_resource_type ~ '^[A-Za-z][A-Za-z0-9._:-]{0,127}$'
                        AND result_related_resource_id IS NOT NULL
                        AND btrim(result_related_resource_id) <> ''
                        AND result_related_resource_id !~ '[[:cntrl:]]'
                        AND result_related_resource_id !~ '^[[:space:]]|[[:space:]]$'
                    )
                )
                AND (
                    result_subresource_id IS NULL
                    OR (
                        btrim(result_subresource_id) <> ''
                        AND result_subresource_id !~ '[[:cntrl:]]'
                        AND result_subresource_id !~ '^[[:space:]]|[[:space:]]$'
                    )
                )
                AND completed_time IS NOT NULL
            )
        ) NOT VALID;

ALTER TABLE fw_idempotency_record
    VALIDATE CONSTRAINT ck_fw_idempotency_result;
