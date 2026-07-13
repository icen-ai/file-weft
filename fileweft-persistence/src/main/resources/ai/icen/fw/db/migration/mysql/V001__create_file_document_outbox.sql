CREATE TABLE fw_file_object
(
    id              varchar(64) PRIMARY KEY,
    tenant_id       varchar(64)   NOT NULL,
    file_name       varchar(512)  NOT NULL,
    content_type    varchar(128),
    file_size       bigint        NOT NULL,
    content_hash    varchar(128),
    storage_type    varchar(64)   NOT NULL,
    storage_path    varchar(1024) NOT NULL,
    status          varchar(32)   NOT NULL,
    created_time    bigint        NOT NULL,
    updated_time    bigint        NOT NULL
);

CREATE INDEX idx_fw_file_tenant_hash ON fw_file_object(tenant_id, content_hash);

CREATE TABLE fw_asset
(
    id              varchar(64) PRIMARY KEY,
    tenant_id       varchar(64) NOT NULL,
    file_id         varchar(64) NOT NULL,
    asset_type      varchar(64) NOT NULL,
    metadata_json   JSON,
    created_time    bigint       NOT NULL,
    updated_time    bigint       NOT NULL
);

CREATE INDEX idx_fw_asset_tenant_file ON fw_asset(tenant_id, file_id);

CREATE TABLE fw_document
(
    id                  varchar(64) PRIMARY KEY,
    tenant_id           varchar(64)  NOT NULL,
    asset_id            varchar(64)  NOT NULL,
    doc_no              varchar(128) NOT NULL,
    title               varchar(512) NOT NULL,
    lifecycle_state     varchar(64)  NOT NULL,
    current_version_id  varchar(64),
    created_time        bigint       NOT NULL,
    updated_time        bigint       NOT NULL,
    UNIQUE(tenant_id, doc_no)
);

CREATE INDEX idx_fw_document_tenant_state ON fw_document(tenant_id, lifecycle_state);
CREATE INDEX idx_fw_document_tenant_updated ON fw_document(tenant_id, updated_time DESC);

CREATE TABLE fw_document_version
(
    id              varchar(64) PRIMARY KEY,
    tenant_id       varchar(64) NOT NULL,
    document_id     varchar(64) NOT NULL,
    version_no      varchar(32) NOT NULL,
    file_id         varchar(64) NOT NULL,
    status          varchar(32),
    created_time    bigint       NOT NULL,
    updated_time    bigint       NOT NULL,
    UNIQUE(tenant_id, document_id, version_no)
);

CREATE INDEX idx_fw_doc_version_tenant_document ON fw_document_version(tenant_id, document_id);

CREATE TABLE fw_outbox_event
(
    id              varchar(64)  PRIMARY KEY,
    tenant_id       varchar(64)  NOT NULL,
    event_type      varchar(128) NOT NULL,
    payload_json    JSON         NOT NULL,
    event_status    varchar(32)  NOT NULL DEFAULT 'PENDING',
    retry_count     integer      NOT NULL DEFAULT 0,
    created_time    bigint       NOT NULL,
    updated_time    bigint       NOT NULL
);

CREATE INDEX idx_fw_outbox_tenant_status ON fw_outbox_event(tenant_id, event_status, created_time);
