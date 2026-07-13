CREATE TABLE fw_idempotency_record
(
    id                           varchar(64)  PRIMARY KEY,
    tenant_id                    varchar(64)  NOT NULL,
    key_digest                   varchar(71)  NOT NULL,
    operator_id                  varchar(256) NOT NULL,
    action                       varchar(128) NOT NULL,
    resource_type                varchar(128) NOT NULL,
    resource_id                  varchar(256) NOT NULL,
    subresource_id               varchar(256),
    request_fingerprint          varchar(71)  NOT NULL,
    record_status                varchar(32)  NOT NULL,
    result_resource_type         varchar(128),
    result_resource_id           varchar(256),
    result_related_resource_type varchar(128),
    result_related_resource_id   varchar(256),
    completed_time               bigint,
    created_time                 bigint       NOT NULL,
    updated_time                 bigint       NOT NULL,

    CONSTRAINT uq_fw_idempotency_tenant_key_digest
        UNIQUE (tenant_id, key_digest),

    CONSTRAINT ck_fw_idempotency_digests
        CHECK (
            key_digest ~ '^sha256:[0-9a-f]{64}$'
            AND request_fingerprint ~ '^sha256:[0-9a-f]{64}$'
        ),

    CONSTRAINT ck_fw_idempotency_binding
        CHECK (
            btrim(id) <> ''
            AND id !~ '[[:cntrl:]]'
            AND id !~ '^[[:space:]]|[[:space:]]$'
            AND btrim(tenant_id) <> ''
            AND tenant_id !~ '[[:cntrl:]]'
            AND tenant_id !~ '^[[:space:]]|[[:space:]]$'
            AND btrim(operator_id) <> ''
            AND operator_id !~ '[[:cntrl:]]'
            AND operator_id !~ '^[[:space:]]|[[:space:]]$'
            AND action ~ '^[A-Za-z][A-Za-z0-9._:-]{0,127}$'
            AND resource_type ~ '^[A-Za-z][A-Za-z0-9._:-]{0,127}$'
            AND btrim(resource_id) <> ''
            AND resource_id !~ '[[:cntrl:]]'
            AND resource_id !~ '^[[:space:]]|[[:space:]]$'
            AND (
                subresource_id IS NULL
                OR (
                    btrim(subresource_id) <> ''
                    AND subresource_id !~ '[[:cntrl:]]'
                    AND subresource_id !~ '^[[:space:]]|[[:space:]]$'
                )
            )
        ),

    CONSTRAINT ck_fw_idempotency_status
        CHECK (record_status IN ('IN_PROGRESS', 'COMPLETED')),

    CONSTRAINT ck_fw_idempotency_result
        CHECK (
            (
                record_status = 'IN_PROGRESS'
                AND result_resource_type IS NULL
                AND result_resource_id IS NULL
                AND result_related_resource_type IS NULL
                AND result_related_resource_id IS NULL
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
                AND completed_time IS NOT NULL
            )
        ),

    CONSTRAINT ck_fw_idempotency_time
        CHECK (
            created_time >= 0
            AND updated_time >= created_time
            AND (record_status <> 'IN_PROGRESS' OR updated_time = created_time)
            AND (
                completed_time IS NULL
                OR (
                    completed_time >= created_time
                    AND completed_time = updated_time
                )
            )
        )
);

CREATE INDEX idx_fw_idempotency_tenant_resource_time
    ON fw_idempotency_record(tenant_id, resource_type, resource_id, created_time DESC, id DESC);

-- A correctly scoped request completes in the same transaction in which this
-- row is inserted. A visible IN_PROGRESS row therefore indicates an
-- integration defect and is indexed only for Doctor/operator diagnosis; it
-- must never be treated as an automatically reclaimable lease.
CREATE INDEX idx_fw_idempotency_in_progress_diagnostic
    ON fw_idempotency_record(updated_time, id)
    WHERE record_status = 'IN_PROGRESS';
