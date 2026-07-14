-- Existing workflows have no reliable submitter evidence. Keep them null so
-- withdrawal authorization fails closed instead of guessing from optional
-- audit rows or expirable idempotency records.
ALTER TABLE fw_workflow_instance
    ADD COLUMN submitted_by varchar(256);

ALTER TABLE fw_workflow_instance
    ADD CONSTRAINT ck_fw_workflow_instance_submitted_by
        CHECK (
            submitted_by IS NULL
            OR (
                char_length(submitted_by) BETWEEN 1 AND 256
                AND char_length(submitted_by)
                    + char_length(regexp_replace(submitted_by, U&'[^\+010000-\+10FFFF]', '', 'g')) <= 256
                AND submitted_by = btrim(submitted_by)
                AND submitted_by !~ U&'[\0001-\001F\007F-\009F]'
                AND submitted_by !~ U&'^[\0020\00A0\1680\2000-\200A\2028-\2029\202F\205F\3000]|[\0020\00A0\1680\2000-\200A\2028-\2029\202F\205F\3000]$'
                AND submitted_by !~ U&'[\00AD\0600-\0605\061C\06DD\070F\0890-\0891\08E2\180E\200B-\200F\202A-\202E\2060-\2064\2066-\206F\FEFF\FFF9-\FFFB\+0110BD\+0110CD\+013430-\+01343F\+01BCA0-\+01BCA3\+01D173-\+01D17A\+0E0001\+0E0020-\+0E007F]'
            )
        ) NOT VALID;

ALTER TABLE fw_workflow_instance
    VALIDATE CONSTRAINT ck_fw_workflow_instance_submitted_by;
