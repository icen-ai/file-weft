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

CREATE INDEX idx_fw_idempotency_in_progress_diagnostic
    ON fw_idempotency_record(updated_time, id)
    WHERE record_status = 'IN_PROGRESS';

-- Note: The PostgreSQL original contains extensive CHECK constraints validating
-- digest formats and identifier content using POSIX regular expressions. Those
-- checks are enforced by the application layer for Kingbase deployments to
-- avoid compatibility differences in regex operator support.
