CREATE TABLE fw_sync_record
(
    id              varchar(64) PRIMARY KEY,
    tenant_id       varchar(64)  NOT NULL,
    document_id     varchar(64)  NOT NULL,
    source_event_id varchar(64)  NOT NULL,
    connector_name  varchar(128) NOT NULL,
    external_id     varchar(512),
    sync_status     varchar(32)  NOT NULL,
    error_message   varchar(1024),
    retry_count     integer      NOT NULL DEFAULT 0,
    created_time    bigint       NOT NULL,
    updated_time    bigint       NOT NULL,
    UNIQUE(tenant_id, source_event_id, connector_name)
);

CREATE INDEX idx_fw_sync_tenant_document_connector
    ON fw_sync_record(tenant_id, document_id, connector_name);
