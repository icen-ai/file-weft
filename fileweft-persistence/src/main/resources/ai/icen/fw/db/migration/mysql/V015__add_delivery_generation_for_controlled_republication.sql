ALTER TABLE fw_document
    ADD COLUMN delivery_generation integer NOT NULL DEFAULT 0;

ALTER TABLE fw_document_delivery_target
    ADD COLUMN delivery_generation integer NOT NULL DEFAULT 0;

-- In MySQL the legacy unique index was explicitly named in V007 so it can be
-- dropped deterministically and replaced with the generation-scoped unique key.
ALTER TABLE fw_document_delivery_target
    DROP INDEX uq_fw_delivery_target_document_target;

ALTER TABLE fw_document_delivery_target
    ADD CONSTRAINT uq_fw_delivery_target_document_generation
        UNIQUE(tenant_id, document_id, target_id, delivery_generation);

CREATE INDEX idx_fw_delivery_tenant_document_generation
    ON fw_document_delivery_target(tenant_id, document_id, delivery_generation);
