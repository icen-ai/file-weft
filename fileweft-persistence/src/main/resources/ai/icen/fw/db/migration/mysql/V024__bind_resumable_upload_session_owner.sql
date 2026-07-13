ALTER TABLE fw_upload_session
    ADD COLUMN owner_id varchar(256);

ALTER TABLE fw_upload_session
    ADD CONSTRAINT ck_fw_upload_session_status
        CHECK (
            session_status IN (
                'ACTIVE', 'COMPLETING', 'COMPLETED', 'ABORTING',
                'ABORTED', 'FAILED', 'EXPIRED', 'QUARANTINED'
            )
        );

-- The Unicode whitespace / invisible-character checks from the PostgreSQL
-- original use POSIX regex and supplementary-code-point logic that MySQL does
-- not support directly. We keep a simpler length/trim check and leave the full
-- identifier contract validation to the application layer.
ALTER TABLE fw_upload_session
    ADD CONSTRAINT ck_fw_upload_session_owner_id
        CHECK (
            owner_id IS NULL
            OR (
                CHAR_LENGTH(owner_id) BETWEEN 1 AND 256
                AND TRIM(owner_id) <> ''
                AND owner_id = TRIM(owner_id)
            )
        );
