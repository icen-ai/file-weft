-- A completed resumable upload is a staging asset until one authorized
-- document command consumes it. The marker is intentionally kept on the
-- owner-scoped upload row so FOR UPDATE and the conditional transition share
-- the same serialization point as upload completion.
ALTER TABLE fw_upload_session
    ADD COLUMN claimed_idempotency_key_digest varchar(71),
    ADD COLUMN claimed_resource_type varchar(128),
    ADD COLUMN claimed_resource_id varchar(64),
    ADD COLUMN claimed_subresource_id varchar(64),
    ADD COLUMN claimed_by varchar(256),
    ADD COLUMN claimed_time bigint;

ALTER TABLE fw_upload_session
    ADD CONSTRAINT ck_fw_upload_session_asset_claim
        CHECK (
            (
                claimed_idempotency_key_digest IS NULL
                AND claimed_resource_type IS NULL
                AND claimed_resource_id IS NULL
                AND claimed_subresource_id IS NULL
                AND claimed_by IS NULL
                AND claimed_time IS NULL
            )
            OR (
                claimed_idempotency_key_digest IS NOT NULL
                AND claimed_idempotency_key_digest ~ '^sha256:[0-9a-f]{64}$'
                AND claimed_resource_type IS NOT NULL
                AND claimed_resource_type = 'DOCUMENT'
                AND claimed_resource_id IS NOT NULL
                AND char_length(claimed_resource_id) BETWEEN 1 AND 64
                AND claimed_subresource_id IS NOT NULL
                AND char_length(claimed_subresource_id) BETWEEN 1 AND 64
                AND owner_id IS NOT NULL
                AND claimed_by IS NOT NULL
                AND claimed_by = owner_id
                AND claimed_time IS NOT NULL
                AND completed_time IS NOT NULL
                AND claimed_time >= completed_time
                AND claimed_time >= created_time
                AND claimed_time < expires_at
                AND claimed_time = updated_time
                AND session_status = 'COMPLETED'
                AND asset_type = 'DOCUMENT'
                AND last_error IS NULL
            )
        ) NOT VALID;

ALTER TABLE fw_upload_session
    VALIDATE CONSTRAINT ck_fw_upload_session_asset_claim;

-- FileAsset ids are generated for one upload session. Enforcing that invariant
-- in the database also prevents a corrupt duplicate session from claiming the
-- same asset through a different upload id.
CREATE UNIQUE INDEX uq_fw_upload_session_tenant_file_asset
    ON fw_upload_session(tenant_id, file_asset_id);
