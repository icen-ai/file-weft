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

-- Existing sessions deliberately remain ownerless. User-facing lookups must require
-- an exact, non-null owner match; system cleanup may still reconcile these rows.
-- The application performs the same validation before persistence. This constraint
-- is defense in depth for direct SQL writers and older/custom application adapters.
ALTER TABLE fw_upload_session
    ADD CONSTRAINT ck_fw_upload_session_owner_id
        CHECK (
            owner_id IS NULL
            OR (
                char_length(owner_id) BETWEEN 1 AND 256
                -- PostgreSQL char_length counts Unicode code points, while the JVM
                -- contract counts UTF-16 code units. Each supplementary code point
                -- therefore consumes one additional unit beyond char_length.
                AND char_length(owner_id)
                    + char_length(regexp_replace(owner_id, U&'[^\+010000-\+10FFFF]', '', 'g')) <= 256
                AND owner_id = btrim(owner_id)
                AND owner_id !~ U&'[\0001-\001F\007F-\009F]'
                AND owner_id !~ U&'^[\0020\00A0\1680\2000-\200A\2028-\2029\202F\205F\3000]|[\0020\00A0\1680\2000-\200A\2028-\2029\202F\205F\3000]$'
                AND owner_id !~ U&'[\00AD\0600-\0605\061C\06DD\070F\0890-\0891\08E2\180E\200B-\200F\202A-\202E\2060-\2064\2066-\206F\FEFF\FFF9-\FFFB\+0110BD\+0110CD\+013430-\+01343F\+01BCA0-\+01BCA3\+01D173-\+01D17A\+0E0001\+0E0020-\+0E007F]'
            )
        );
