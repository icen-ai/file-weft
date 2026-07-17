CREATE TABLE fw_presigned_upload_session (
    id varchar(64) NOT NULL,
    tenant_id varchar(64) NOT NULL,
    owner_id varchar(256) NOT NULL,
    idempotency_key_digest varchar(71) NOT NULL,
    declaration_digest varchar(71) NOT NULL,
    file_name varchar(1024) NOT NULL,
    content_length bigint NOT NULL,
    content_type varchar(256) NOT NULL,
    content_hash varchar(512) NOT NULL,
    checksum_algorithm varchar(64) NOT NULL,
    checksum_value text NOT NULL,
    metadata_json json NOT NULL,
    staging_storage_type varchar(64) NOT NULL,
    staging_storage_path text NOT NULL,
    staging_location_digest varchar(71) NOT NULL,
    required_headers_json json NOT NULL,
    grant_duration_millis bigint NOT NULL,
    grant_expires_at bigint NOT NULL,
    session_expires_at bigint NOT NULL,
    session_status varchar(32) NOT NULL,
    row_version bigint NOT NULL,
    claim_token varchar(71),
    claim_time bigint,
    claim_expires_at bigint,
    final_storage_type varchar(64),
    final_storage_path text,
    final_location_digest varchar(71),
    provider_revision text,
    final_content_length bigint,
    final_content_type varchar(256),
    final_content_hash varchar(512),
    final_checksum_algorithm varchar(64),
    final_checksum_value text,
    final_metadata_json json,
    last_error_class varchar(2048),
    completed_time bigint,
    cancelled_time bigint,
    cleanup_time bigint,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_fw_presigned_upload_identity CHECK (
        CHAR_LENGTH(id) BETWEEN 1 AND 64
        AND CHAR_LENGTH(tenant_id) BETWEEN 1 AND 64
        AND CHAR_LENGTH(owner_id) BETWEEN 1 AND 256
        AND idempotency_key_digest REGEXP '^sha256:[0-9a-f]{64}$'
        AND declaration_digest REGEXP '^sha256:[0-9a-f]{64}$'
        AND staging_location_digest REGEXP '^sha256:[0-9a-f]{64}$'
        AND (final_location_digest IS NULL OR final_location_digest REGEXP '^sha256:[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_fw_presigned_upload_declaration CHECK (
        content_length >= 0
        AND CHAR_LENGTH(file_name) BETWEEN 1 AND 1024
        AND CHAR_LENGTH(content_type) BETWEEN 1 AND 256
        AND CHAR_LENGTH(content_hash) BETWEEN 1 AND 512
        AND CHAR_LENGTH(checksum_algorithm) BETWEEN 1 AND 64
        AND CHAR_LENGTH(checksum_value) BETWEEN 1 AND 1024
        AND JSON_TYPE(metadata_json) = 'OBJECT'
        AND JSON_TYPE(required_headers_json) = 'OBJECT'
        AND CHAR_LENGTH(staging_storage_type) BETWEEN 1 AND 64
        AND CHAR_LENGTH(staging_storage_path) BETWEEN 1 AND 4096
    ),
    CONSTRAINT ck_fw_presigned_upload_lifetime CHECK (
        created_time >= 0
        AND updated_time >= created_time
        AND grant_duration_millis > 0
        AND grant_expires_at > created_time
        AND session_expires_at >= grant_expires_at
        AND row_version >= 0
        AND (last_error_class IS NULL OR CHAR_LENGTH(last_error_class) BETWEEN 1 AND 2048)
    ),
    CONSTRAINT ck_fw_presigned_upload_status CHECK (
        session_status IN ('READY', 'FINALIZING', 'COMPLETED', 'CANCELLED', 'EXPIRED')
    ),
    CONSTRAINT ck_fw_presigned_upload_claim CHECK (
        (
            session_status = 'FINALIZING'
            AND claim_token IS NOT NULL
            AND claim_token REGEXP '^sha256:[0-9a-f]{64}$'
            AND claim_time IS NOT NULL
            AND claim_expires_at IS NOT NULL
            AND claim_time >= created_time
            AND claim_time = updated_time
            AND claim_expires_at > claim_time
            AND claim_expires_at <= session_expires_at
        )
        OR (
            session_status <> 'FINALIZING'
            AND claim_token IS NULL
            AND claim_time IS NULL
            AND claim_expires_at IS NULL
        )
    ),
    CONSTRAINT ck_fw_presigned_upload_finalization CHECK (
        (
            session_status = 'COMPLETED'
            AND final_storage_type IS NOT NULL
            AND CHAR_LENGTH(final_storage_type) BETWEEN 1 AND 64
            AND final_storage_path IS NOT NULL
            AND CHAR_LENGTH(final_storage_path) BETWEEN 1 AND 4096
            AND final_location_digest IS NOT NULL
            AND final_location_digest <> staging_location_digest
            AND provider_revision IS NOT NULL
            AND CHAR_LENGTH(provider_revision) BETWEEN 1 AND 2048
            AND final_content_length IS NOT NULL
            AND final_content_length = content_length
            AND final_content_type IS NOT NULL
            AND final_content_type = content_type
            AND final_content_hash IS NOT NULL
            AND final_content_hash = content_hash
            AND final_checksum_algorithm IS NOT NULL
            AND final_checksum_algorithm = checksum_algorithm
            AND final_checksum_value IS NOT NULL
            AND final_checksum_value = checksum_value
            AND final_metadata_json IS NOT NULL
            AND JSON_TYPE(final_metadata_json) = 'OBJECT'
            AND final_metadata_json = metadata_json
            AND completed_time IS NOT NULL
            AND completed_time >= created_time
            AND completed_time = updated_time
            AND last_error_class IS NULL
        )
        OR (
            session_status <> 'COMPLETED'
            AND final_storage_type IS NULL
            AND final_storage_path IS NULL
            AND final_location_digest IS NULL
            AND provider_revision IS NULL
            AND final_content_length IS NULL
            AND final_content_type IS NULL
            AND final_content_hash IS NULL
            AND final_checksum_algorithm IS NULL
            AND final_checksum_value IS NULL
            AND final_metadata_json IS NULL
            AND completed_time IS NULL
        )
    ),
    CONSTRAINT ck_fw_presigned_upload_cancellation CHECK (
        (
            session_status = 'CANCELLED'
            AND cancelled_time IS NOT NULL
            AND cancelled_time >= created_time
            AND cancelled_time <= updated_time
        )
        OR (session_status <> 'CANCELLED' AND cancelled_time IS NULL)
    ),
    CONSTRAINT ck_fw_presigned_upload_cleanup CHECK (
        cleanup_time IS NULL
        OR (
            session_status IN ('CANCELLED', 'EXPIRED')
            AND cleanup_time >= grant_expires_at
            AND cleanup_time = updated_time
        )
    ),
    UNIQUE KEY uq_fw_presigned_upload_owner_idempotency (tenant_id, owner_id, idempotency_key_digest),
    UNIQUE KEY uq_fw_presigned_upload_staging_location (staging_location_digest),
    UNIQUE KEY uq_fw_presigned_upload_final_location (final_location_digest),
    KEY ix_fw_presigned_upload_recovery (session_status, claim_expires_at, session_expires_at),
    KEY ix_fw_presigned_upload_cleanup (cleanup_time, updated_time, grant_expires_at, session_status, session_expires_at),
    KEY ix_fw_presigned_upload_owner_updated (tenant_id, owner_id, updated_time, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
