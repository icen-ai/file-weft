ALTER TABLE fw_dev_platform_document
    ADD COLUMN target_id varchar(128) NOT NULL DEFAULT 'default';

ALTER TABLE fw_dev_platform_document
    DROP CONSTRAINT fw_dev_platform_document_tenant_id_document_id_key;

ALTER TABLE fw_dev_platform_document
    ADD CONSTRAINT uq_fw_dev_platform_document_target UNIQUE (tenant_id, document_id, target_id);

CREATE INDEX idx_fw_dev_platform_document_target_updated
    ON fw_dev_platform_document (target_id, updated_time DESC);
