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

-- The Unicode identifier contract from the PostgreSQL original is enforced by
-- the application layer for Kingbase to avoid regex/unicode escape differences.
ALTER TABLE fw_workflow_task VALIDATE CONSTRAINT ck_fw_workflow_task_decision_evidence;
