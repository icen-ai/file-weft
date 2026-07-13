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
-- The Unicode identifier contract from the PostgreSQL original is enforced by the
-- application layer for Kingbase.
ALTER TABLE fw_upload_session
    ADD CONSTRAINT ck_fw_upload_session_owner_id
        CHECK (
            owner_id IS NULL
            OR (
                char_length(owner_id) BETWEEN 1 AND 256
                AND btrim(owner_id) <> ''
                AND owner_id = btrim(owner_id)
            )
        );
