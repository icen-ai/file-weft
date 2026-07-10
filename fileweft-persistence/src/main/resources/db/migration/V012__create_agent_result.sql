CREATE TABLE fw_agent_result
(
    id                varchar(64)  PRIMARY KEY,
    tenant_id         varchar(64)  NOT NULL,
    task_id           varchar(64)  NOT NULL,
    capability        varchar(32)  NOT NULL,
    source_event_id   varchar(64)  NOT NULL,
    source_event_type varchar(128) NOT NULL,
    result_status     varchar(32)  NOT NULL,
    result_json       jsonb        NOT NULL,
    created_time      bigint       NOT NULL,
    updated_time      bigint       NOT NULL,
    UNIQUE (tenant_id, task_id)
);

CREATE INDEX idx_fw_agent_result_tenant_source_time
    ON fw_agent_result(tenant_id, source_event_id, created_time DESC);

CREATE TABLE fw_agent_suggestion_confirmation
(
    id            varchar(64) PRIMARY KEY,
    tenant_id     varchar(64) NOT NULL,
    task_id       varchar(64) NOT NULL,
    suggestion_id varchar(64) NOT NULL,
    confirmed_by  varchar(64) NOT NULL,
    confirmed_time bigint     NOT NULL,
    created_time  bigint      NOT NULL,
    updated_time  bigint      NOT NULL,
    UNIQUE (tenant_id, task_id, suggestion_id)
);

CREATE INDEX idx_fw_agent_confirmation_tenant_task_time
    ON fw_agent_suggestion_confirmation(tenant_id, task_id, confirmed_time DESC);
