CREATE TABLE fw_upload_session
(
    id                  varchar(64)   PRIMARY KEY,
    tenant_id           varchar(64)   NOT NULL,
    idempotency_key     varchar(256)  NOT NULL,
    storage_upload_id   varchar(512)  NOT NULL,
    storage_type        varchar(64)   NOT NULL,
    storage_path        varchar(1024) NOT NULL,
    file_object_id      varchar(64)   NOT NULL,
    file_asset_id       varchar(64)   NOT NULL,
    file_name           varchar(512)  NOT NULL,
    content_length      bigint        NOT NULL,
    asset_type          varchar(64)   NOT NULL,
    content_type        varchar(128),
    content_hash        varchar(128),
    metadata_json       jsonb         NOT NULL DEFAULT '{}'::jsonb,
    session_status      varchar(32)   NOT NULL,
    expires_at          bigint        NOT NULL,
    last_error          varchar(2048),
    completed_time      bigint,
    created_time        bigint        NOT NULL,
    updated_time        bigint        NOT NULL,
    UNIQUE (tenant_id, idempotency_key),
    UNIQUE (tenant_id, storage_type, storage_upload_id)
);

CREATE INDEX idx_fw_upload_session_expiry
    ON fw_upload_session(session_status, expires_at, created_time);

CREATE INDEX idx_fw_upload_session_tenant_time
    ON fw_upload_session(tenant_id, updated_time DESC);

CREATE TABLE fw_upload_session_part
(
    id              varchar(64)  PRIMARY KEY,
    tenant_id       varchar(64)  NOT NULL,
    session_id      varchar(64)  NOT NULL,
    part_number     integer      NOT NULL,
    part_etag       varchar(512) NOT NULL,
    content_length  bigint       NOT NULL,
    created_time    bigint       NOT NULL,
    updated_time    bigint       NOT NULL,
    UNIQUE (tenant_id, session_id, part_number)
);

CREATE INDEX idx_fw_upload_part_tenant_session
    ON fw_upload_session_part(tenant_id, session_id, part_number);
