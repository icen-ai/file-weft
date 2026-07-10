
-- FileWeft PostgreSQL Core Schema


CREATE TABLE fw_file_object
(
    id              varchar(64) PRIMARY KEY,
    tenant_id       varchar(64) NOT NULL,
    file_name       varchar(512) NOT NULL,
    content_type    varchar(128),
    file_size       bigint NOT NULL,
    content_hash    varchar(128),
    storage_type    varchar(64) NOT NULL,
    storage_path    varchar(1024) NOT NULL,
    status          varchar(32) NOT NULL,
    created_time    bigint NOT NULL,
    updated_time    bigint NOT NULL
);

CREATE INDEX idx_fw_file_tenant_hash
ON fw_file_object(tenant_id, content_hash);



CREATE TABLE fw_asset
(
    id              varchar(64) PRIMARY KEY,
    tenant_id       varchar(64) NOT NULL,
    file_id         varchar(64) NOT NULL,
    asset_type      varchar(64) NOT NULL,
    metadata_json   jsonb,
    created_time    bigint NOT NULL,
    updated_time    bigint NOT NULL
);

CREATE INDEX idx_fw_asset_file
ON fw_asset(file_id);



CREATE TABLE fw_document
(
    id                  varchar(64) PRIMARY KEY,
    tenant_id           varchar(64) NOT NULL,
    asset_id            varchar(64) NOT NULL,
    doc_no              varchar(128) NOT NULL,
    title               varchar(512) NOT NULL,
    lifecycle_state     varchar(64) NOT NULL,
    current_version_id  varchar(64),
    created_time        bigint NOT NULL,
    updated_time        bigint NOT NULL,

    UNIQUE(tenant_id, doc_no)
);

CREATE INDEX idx_fw_document_state
ON fw_document(tenant_id, lifecycle_state);



CREATE TABLE fw_document_version
(
    id              varchar(64) PRIMARY KEY,
    tenant_id       varchar(64) NOT NULL,
    document_id     varchar(64) NOT NULL,
    version_no      varchar(32) NOT NULL,
    file_id         varchar(64) NOT NULL,
    status          varchar(32),
    created_time    bigint NOT NULL,
    updated_time    bigint NOT NULL
);

CREATE INDEX idx_fw_doc_version
ON fw_document_version(document_id, version_no);



CREATE TABLE fw_workflow_instance
(
    id              varchar(64) PRIMARY KEY,
    tenant_id       varchar(64) NOT NULL,
    document_id     varchar(64) NOT NULL,
    workflow_type   varchar(64) NOT NULL,
    state           varchar(64) NOT NULL,
    created_time    bigint NOT NULL,
    updated_time    bigint NOT NULL
);



CREATE TABLE fw_workflow_task
(
    id              varchar(64) PRIMARY KEY,
    tenant_id       varchar(64) NOT NULL,
    workflow_id     varchar(64) NOT NULL,
    assignee_id     varchar(64),
    task_state      varchar(32),
    comment_text    text,
    created_time    bigint NOT NULL,
    updated_time    bigint NOT NULL
);



CREATE TABLE fw_sync_record
(
    id              varchar(64) PRIMARY KEY,
    tenant_id       varchar(64) NOT NULL,
    document_id     varchar(64) NOT NULL,
    connector_name  varchar(128) NOT NULL,
    external_id     varchar(512),
    sync_status     varchar(32) NOT NULL,
    error_message   text,
    retry_count     integer DEFAULT 0,
    created_time    bigint NOT NULL,
    updated_time    bigint NOT NULL
);

CREATE INDEX idx_fw_sync_document
ON fw_sync_record(document_id, connector_name);



CREATE TABLE fw_outbox_event
(
    id              varchar(64) PRIMARY KEY,
    tenant_id       varchar(64) NOT NULL,
    event_type      varchar(128) NOT NULL,
    payload_json    jsonb NOT NULL,
    event_status    varchar(32),
    retry_count     integer DEFAULT 0,
    created_time    bigint NOT NULL
);



CREATE TABLE fw_task
(
    id              varchar(64) PRIMARY KEY,
    tenant_id       varchar(64) NOT NULL,
    task_type       varchar(64) NOT NULL,
    business_id     varchar(64),
    task_status     varchar(32),
    error_message   text,
    created_time    bigint NOT NULL,
    updated_time    bigint NOT NULL
);



CREATE TABLE fw_operation_log
(
    id              varchar(64) PRIMARY KEY,
    tenant_id       varchar(64) NOT NULL,
    resource_type   varchar(64),
    resource_id     varchar(64),
    action          varchar(64),
    operator_id     varchar(64),
    detail_json     jsonb,
    created_time    bigint NOT NULL
);
