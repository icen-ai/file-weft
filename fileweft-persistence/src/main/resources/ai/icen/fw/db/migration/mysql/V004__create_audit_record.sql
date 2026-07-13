CREATE TABLE fw_audit_record
(
    id              varchar(64) PRIMARY KEY,
    tenant_id       varchar(64)  NOT NULL,
    resource_type   varchar(64)  NOT NULL,
    resource_id     varchar(64)  NOT NULL,
    action          varchar(128) NOT NULL,
    operator_id     varchar(64),
    detail_json     JSON,
    created_time    bigint       NOT NULL,
    updated_time    bigint       NOT NULL
);

CREATE INDEX idx_fw_audit_tenant_resource_time
    ON fw_audit_record(tenant_id, resource_type, resource_id, created_time DESC);
