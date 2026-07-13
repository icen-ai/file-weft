ALTER TABLE fw_audit_record
    MODIFY COLUMN operator_id varchar(256);

ALTER TABLE fw_operation_log
    MODIFY COLUMN operator_id varchar(256);

ALTER TABLE fw_agent_suggestion_confirmation
    MODIFY COLUMN confirmed_by varchar(256) NOT NULL;

ALTER TABLE fw_workflow_task
    MODIFY COLUMN assignee_id varchar(256),
    ADD COLUMN decision_operator_id varchar(256),
    ADD COLUMN decision_operator_name varchar(256),
    ADD COLUMN decided_time bigint;

-- The full Unicode identifier contract checks from the PostgreSQL original
-- (supplementary code points, invisible characters, bidirectional marks) are
-- enforced by the application layer for MySQL. We keep the core evidence
-- consistency check, which MySQL 8.0.16+ can enforce.
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
        );
