CREATE TABLE fw_dev_platform_document
(
    id                  varchar(64) PRIMARY KEY,
    tenant_id           varchar(64)   NOT NULL,
    document_id         varchar(64)   NOT NULL,
    external_id         varchar(256)  NOT NULL,
    file_name           varchar(512)  NOT NULL,
    content_type        varchar(128),
    content_hash        varchar(128),
    download_uri        varchar(2048) NOT NULL,
    downloaded_bytes    bigint        NOT NULL,
    last_idempotency_key varchar(256) NOT NULL,
    created_time        bigint        NOT NULL,
    updated_time        bigint        NOT NULL,
    UNIQUE (tenant_id, document_id)
);

CREATE INDEX idx_fw_dev_platform_document_tenant_updated
    ON fw_dev_platform_document (tenant_id, updated_time DESC);
