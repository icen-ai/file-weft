ALTER TABLE fw_document
    ADD COLUMN delivery_generation integer NOT NULL DEFAULT 0;

ALTER TABLE fw_document_delivery_target
    ADD COLUMN delivery_generation integer NOT NULL DEFAULT 0;

DO $$
DECLARE
    legacy_constraint text;
BEGIN
    SELECT conname
    INTO legacy_constraint
    FROM pg_constraint
    WHERE conrelid = 'fw_document_delivery_target'::regclass
      AND contype = 'u'
      AND pg_get_constraintdef(oid) = 'UNIQUE (tenant_id, document_id, target_id)';

    IF legacy_constraint IS NOT NULL THEN
        EXECUTE format('ALTER TABLE fw_document_delivery_target DROP CONSTRAINT %I', legacy_constraint);
    END IF;
END $$;

ALTER TABLE fw_document_delivery_target
    ADD CONSTRAINT uq_fw_delivery_target_document_generation
        UNIQUE(tenant_id, document_id, target_id, delivery_generation);

CREATE INDEX idx_fw_delivery_tenant_document_generation
    ON fw_document_delivery_target(tenant_id, document_id, delivery_generation);
