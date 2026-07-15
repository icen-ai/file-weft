ALTER TABLE fw_presigned_upload_session ADD COLUMN asset_file_object_id varchar(64);
ALTER TABLE fw_presigned_upload_session ADD COLUMN asset_file_asset_id varchar(64);
ALTER TABLE fw_presigned_upload_session ADD COLUMN asset_claim_key_digest varchar(71);
ALTER TABLE fw_presigned_upload_session ADD COLUMN asset_claim_purpose varchar(64);
ALTER TABLE fw_presigned_upload_session ADD COLUMN asset_claimed_by varchar(256);
ALTER TABLE fw_presigned_upload_session ADD COLUMN asset_claimed_time bigint;

ALTER TABLE fw_presigned_upload_session
    ADD CONSTRAINT ck_fw_presigned_upload_asset_claim CHECK (
        (
            asset_file_object_id IS NULL
            AND asset_file_asset_id IS NULL
            AND asset_claim_key_digest IS NULL
            AND asset_claim_purpose IS NULL
            AND asset_claimed_by IS NULL
            AND asset_claimed_time IS NULL
        )
        OR (
            session_status = 'COMPLETED'
            AND asset_file_object_id IS NOT NULL
            AND char_length(asset_file_object_id) BETWEEN 1 AND 64
            AND asset_file_asset_id IS NOT NULL
            AND char_length(asset_file_asset_id) BETWEEN 1 AND 64
            AND asset_claim_key_digest IS NOT NULL
            AND asset_claim_key_digest ~ '^sha256:[0-9a-f]{64}$'
            AND asset_claim_purpose IS NOT NULL
            AND asset_claim_purpose = 'DOCUMENT'
            AND asset_claimed_by IS NOT NULL
            AND char_length(asset_claimed_by) BETWEEN 1 AND 256
            AND asset_claimed_by = owner_id
            AND asset_claimed_time IS NOT NULL
            AND completed_time IS NOT NULL
            AND asset_claimed_time >= completed_time
            AND asset_claimed_time >= created_time
            AND asset_claimed_time < session_expires_at
            AND last_error_class IS NULL
        )
    );

CREATE UNIQUE INDEX uq_fw_presigned_asset_file_object
    ON fw_presigned_upload_session(tenant_id, asset_file_object_id);

CREATE UNIQUE INDEX uq_fw_presigned_asset_file_asset
    ON fw_presigned_upload_session(tenant_id, asset_file_asset_id);
