CREATE TABLE fw_workflow_instance
(
    id              varchar(64) PRIMARY KEY,
    tenant_id       varchar(64) NOT NULL,
    document_id     varchar(64) NOT NULL,
    workflow_type   varchar(64) NOT NULL,
    state           varchar(32) NOT NULL,
    created_time    bigint      NOT NULL,
    updated_time    bigint      NOT NULL
);

CREATE INDEX idx_fw_workflow_tenant_document_state
    ON fw_workflow_instance(tenant_id, document_id, state);

CREATE TABLE fw_workflow_task
(
    id              varchar(64) PRIMARY KEY,
    tenant_id       varchar(64) NOT NULL,
    workflow_id     varchar(64) NOT NULL,
    assignee_id     varchar(64),
    task_state      varchar(32) NOT NULL,
    comment_text    longtext,
    created_time    bigint      NOT NULL,
    updated_time    bigint      NOT NULL
);

CREATE INDEX idx_fw_workflow_task_tenant_workflow
    ON fw_workflow_task(tenant_id, workflow_id);
