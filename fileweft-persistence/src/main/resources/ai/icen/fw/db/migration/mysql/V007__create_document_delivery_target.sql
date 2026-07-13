CREATE TABLE fw_document_delivery_target
(
    id                   varchar(64) PRIMARY KEY,
    tenant_id            varchar(64)  NOT NULL,
    document_id          varchar(64)  NOT NULL,
    profile_id           varchar(128) NOT NULL,
    target_id            varchar(128) NOT NULL,
    target_name          varchar(256) NOT NULL,
    connector_id         varchar(128) NOT NULL,
    delivery_requirement varchar(16)  NOT NULL,
    owner_ref            varchar(256),
    delivery_status      varchar(32)  NOT NULL,
    external_id          varchar(512),
    error_message        varchar(1024),
    retry_count          integer      NOT NULL DEFAULT 0,
    created_time         bigint       NOT NULL,
    updated_time         bigint       NOT NULL,
    CONSTRAINT uq_fw_delivery_target_document_target UNIQUE(tenant_id, document_id, target_id)
);

CREATE INDEX idx_fw_delivery_tenant_document
    ON fw_document_delivery_target(tenant_id, document_id);

CREATE INDEX idx_fw_delivery_tenant_status
    ON fw_document_delivery_target(tenant_id, delivery_status);
