-- Host-owned user identifiers are opaque strings. Keep every persisted user
-- reference aligned with FileWeft's 256 UTF-16-unit application boundary.
ALTER TABLE fw_audit_record
    ALTER COLUMN operator_id TYPE varchar(256);

ALTER TABLE fw_operation_log
    ALTER COLUMN operator_id TYPE varchar(256);

ALTER TABLE fw_agent_suggestion_confirmation
    ALTER COLUMN confirmed_by TYPE varchar(256);

ALTER TABLE fw_workflow_task
    ALTER COLUMN assignee_id TYPE varchar(256),
    ADD COLUMN decision_operator_id varchar(256),
    ADD COLUMN decision_operator_name varchar(256),
    ADD COLUMN decided_time bigint;

-- Existing completed tasks deliberately remain without decision evidence. It
-- cannot be reconstructed safely from optional audit rows. New decisions write
-- all identity/time fields atomically with the task state transition.
ALTER TABLE fw_workflow_task
    ADD CONSTRAINT ck_fw_workflow_task_decision_evidence
        CHECK (
            (decision_operator_name IS NULL OR decision_operator_id IS NOT NULL)
            AND (
                (decision_operator_id IS NULL AND decided_time IS NULL)
                OR (decision_operator_id IS NOT NULL AND decided_time IS NOT NULL)
            )
            AND (
                decision_operator_id IS NULL
                OR task_state IN ('APPROVED', 'REJECTED')
            )
            AND (
                task_state <> 'PENDING'
                OR (
                    decision_operator_id IS NULL
                    AND decision_operator_name IS NULL
                    AND decided_time IS NULL
                )
            )
            AND (
                decided_time IS NULL
                OR (decided_time >= created_time AND decided_time <= updated_time)
            )
        ) NOT VALID;

-- Use the same fixed identifier contract for all host-user columns. The
-- supplementary-code-point expression converts PostgreSQL code-point length
-- to the JVM UTF-16-unit limit used by the public application boundary.
ALTER TABLE fw_audit_record
    ADD CONSTRAINT ck_fw_audit_operator_id
        CHECK (
            operator_id IS NULL
            OR (
                char_length(operator_id) BETWEEN 1 AND 256
                AND char_length(operator_id)
                    + char_length(regexp_replace(operator_id, U&'[^\+010000-\+10FFFF]', '', 'g')) <= 256
                AND operator_id = btrim(operator_id)
                AND operator_id !~ U&'[\0001-\001F\007F-\009F]'
                AND operator_id !~ U&'^[\0020\00A0\1680\2000-\200A\2028-\2029\202F\205F\3000]|[\0020\00A0\1680\2000-\200A\2028-\2029\202F\205F\3000]$'
                AND operator_id !~ U&'[\00AD\0600-\0605\061C\06DD\070F\0890-\0891\08E2\180E\200B-\200F\202A-\202E\2060-\2064\2066-\206F\FEFF\FFF9-\FFFB\+0110BD\+0110CD\+013430-\+01343F\+01BCA0-\+01BCA3\+01D173-\+01D17A\+0E0001\+0E0020-\+0E007F]'
            )
        ) NOT VALID;

ALTER TABLE fw_operation_log
    ADD CONSTRAINT ck_fw_operation_operator_id
        CHECK (
            operator_id IS NULL
            OR (
                char_length(operator_id) BETWEEN 1 AND 256
                AND char_length(operator_id)
                    + char_length(regexp_replace(operator_id, U&'[^\+010000-\+10FFFF]', '', 'g')) <= 256
                AND operator_id = btrim(operator_id)
                AND operator_id !~ U&'[\0001-\001F\007F-\009F]'
                AND operator_id !~ U&'^[\0020\00A0\1680\2000-\200A\2028-\2029\202F\205F\3000]|[\0020\00A0\1680\2000-\200A\2028-\2029\202F\205F\3000]$'
                AND operator_id !~ U&'[\00AD\0600-\0605\061C\06DD\070F\0890-\0891\08E2\180E\200B-\200F\202A-\202E\2060-\2064\2066-\206F\FEFF\FFF9-\FFFB\+0110BD\+0110CD\+013430-\+01343F\+01BCA0-\+01BCA3\+01D173-\+01D17A\+0E0001\+0E0020-\+0E007F]'
            )
        ) NOT VALID;

ALTER TABLE fw_agent_suggestion_confirmation
    ADD CONSTRAINT ck_fw_agent_confirmation_operator_id
        CHECK (
            char_length(confirmed_by) BETWEEN 1 AND 256
            AND char_length(confirmed_by)
                + char_length(regexp_replace(confirmed_by, U&'[^\+010000-\+10FFFF]', '', 'g')) <= 256
            AND confirmed_by = btrim(confirmed_by)
            AND confirmed_by !~ U&'[\0001-\001F\007F-\009F]'
            AND confirmed_by !~ U&'^[\0020\00A0\1680\2000-\200A\2028-\2029\202F\205F\3000]|[\0020\00A0\1680\2000-\200A\2028-\2029\202F\205F\3000]$'
            AND confirmed_by !~ U&'[\00AD\0600-\0605\061C\06DD\070F\0890-\0891\08E2\180E\200B-\200F\202A-\202E\2060-\2064\2066-\206F\FEFF\FFF9-\FFFB\+0110BD\+0110CD\+013430-\+01343F\+01BCA0-\+01BCA3\+01D173-\+01D17A\+0E0001\+0E0020-\+0E007F]'
        ) NOT VALID;

ALTER TABLE fw_workflow_task
    ADD CONSTRAINT ck_fw_workflow_task_assignee_id
        CHECK (
            assignee_id IS NULL
            OR (
                char_length(assignee_id) BETWEEN 1 AND 256
                AND char_length(assignee_id)
                    + char_length(regexp_replace(assignee_id, U&'[^\+010000-\+10FFFF]', '', 'g')) <= 256
                AND assignee_id = btrim(assignee_id)
                AND assignee_id !~ U&'[\0001-\001F\007F-\009F]'
                AND assignee_id !~ U&'^[\0020\00A0\1680\2000-\200A\2028-\2029\202F\205F\3000]|[\0020\00A0\1680\2000-\200A\2028-\2029\202F\205F\3000]$'
                AND assignee_id !~ U&'[\00AD\0600-\0605\061C\06DD\070F\0890-\0891\08E2\180E\200B-\200F\202A-\202E\2060-\2064\2066-\206F\FEFF\FFF9-\FFFB\+0110BD\+0110CD\+013430-\+01343F\+01BCA0-\+01BCA3\+01D173-\+01D17A\+0E0001\+0E0020-\+0E007F]'
            )
        ) NOT VALID,
    ADD CONSTRAINT ck_fw_workflow_task_decision_operator_id
        CHECK (
            decision_operator_id IS NULL
            OR (
                char_length(decision_operator_id) BETWEEN 1 AND 256
                AND char_length(decision_operator_id)
                    + char_length(regexp_replace(decision_operator_id, U&'[^\+010000-\+10FFFF]', '', 'g')) <= 256
                AND decision_operator_id = btrim(decision_operator_id)
                AND decision_operator_id !~ U&'[\0001-\001F\007F-\009F]'
                AND decision_operator_id !~ U&'^[\0020\00A0\1680\2000-\200A\2028-\2029\202F\205F\3000]|[\0020\00A0\1680\2000-\200A\2028-\2029\202F\205F\3000]$'
                AND decision_operator_id !~ U&'[\00AD\0600-\0605\061C\06DD\070F\0890-\0891\08E2\180E\200B-\200F\202A-\202E\2060-\2064\2066-\206F\FEFF\FFF9-\FFFB\+0110BD\+0110CD\+013430-\+01343F\+01BCA0-\+01BCA3\+01D173-\+01D17A\+0E0001\+0E0020-\+0E007F]'
            )
        ) NOT VALID;

ALTER TABLE fw_workflow_task VALIDATE CONSTRAINT ck_fw_workflow_task_decision_evidence;
ALTER TABLE fw_audit_record VALIDATE CONSTRAINT ck_fw_audit_operator_id;
ALTER TABLE fw_operation_log VALIDATE CONSTRAINT ck_fw_operation_operator_id;
ALTER TABLE fw_agent_suggestion_confirmation VALIDATE CONSTRAINT ck_fw_agent_confirmation_operator_id;
ALTER TABLE fw_workflow_task VALIDATE CONSTRAINT ck_fw_workflow_task_assignee_id;
ALTER TABLE fw_workflow_task VALIDATE CONSTRAINT ck_fw_workflow_task_decision_operator_id;
