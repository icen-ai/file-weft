CREATE TABLE fw_operation_log
(
    id            varchar(64) PRIMARY KEY,
    tenant_id     varchar(64) NOT NULL,
    resource_type varchar(64) NOT NULL,
    resource_id   varchar(64) NOT NULL,
    action        varchar(128) NOT NULL,
    operator_id   varchar(64),
    operator_name varchar(256),
    trace_id      varchar(128),
    detail_json   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_time  bigint      NOT NULL
);

CREATE INDEX idx_fw_operation_tenant_resource_time
    ON fw_operation_log(tenant_id, resource_type, resource_id, created_time DESC);

CREATE INDEX idx_fw_operation_tenant_trace_time
    ON fw_operation_log(tenant_id, trace_id, created_time DESC);
