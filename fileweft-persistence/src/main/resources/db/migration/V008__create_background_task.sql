CREATE TABLE fw_task
(
    id                varchar(64) PRIMARY KEY,
    tenant_id         varchar(64)  NOT NULL,
    task_type         varchar(128) NOT NULL,
    business_id       varchar(64),
    payload_json      jsonb        NOT NULL DEFAULT '{}'::jsonb,
    idempotency_key   varchar(256) NOT NULL,
    task_status       varchar(32)  NOT NULL,
    retry_count       integer      NOT NULL DEFAULT 0,
    next_attempt_time bigint       NOT NULL DEFAULT 0,
    lease_owner       varchar(256),
    lease_expire_time bigint       NOT NULL DEFAULT 0,
    last_error        varchar(1024),
    created_time      bigint       NOT NULL,
    updated_time      bigint       NOT NULL,
    UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_fw_task_eligible
    ON fw_task(task_status, next_attempt_time, created_time);

CREATE INDEX idx_fw_task_tenant_business_status
    ON fw_task(tenant_id, business_id, task_status, updated_time DESC);
