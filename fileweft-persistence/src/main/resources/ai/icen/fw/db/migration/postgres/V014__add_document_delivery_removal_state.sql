ALTER TABLE fw_document_delivery_target
    ADD COLUMN removal_status varchar(32) NOT NULL DEFAULT 'NOT_REQUESTED',
    ADD COLUMN removal_error_message varchar(1024),
    ADD COLUMN removal_retry_count integer NOT NULL DEFAULT 0;

CREATE INDEX idx_fw_delivery_tenant_removal_status
    ON fw_document_delivery_target(tenant_id, removal_status);
