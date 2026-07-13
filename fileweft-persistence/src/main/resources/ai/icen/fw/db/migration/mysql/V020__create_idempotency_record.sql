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
        CHECK (record_status IN ('IN_PROGRESS', 'COMPLETED'))
);

CREATE INDEX idx_fw_idempotency_tenant_resource_time
    ON fw_idempotency_record(tenant_id, resource_type, resource_id, created_time DESC, id DESC);

CREATE INDEX idx_fw_idempotency_in_progress_diagnostic
    ON fw_idempotency_record(updated_time, id);

-- Note: The original PostgreSQL migration contains extensive CHECK constraints
-- validating digest formats, identifier content, status/result consistency, and
-- timestamp ordering. MySQL 8.0.16+ enforces CHECK constraints, but the regex
-- and trim logic is expressed using POSIX operators and btrim() which are not
-- available in MySQL. These validations are intentionally simplified here and
-- remain enforced by the application layer for MySQL deployments.
