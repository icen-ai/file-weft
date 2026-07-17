-- Immutable human-task claim/delegation/transfer evidence. V030/V031 remain unchanged.
ALTER TABLE fw_wf_human_task ADD COLUMN active_delegate_type varchar(64);
ALTER TABLE fw_wf_human_task ADD COLUMN active_delegate_id varbinary(512);
ALTER TABLE fw_wf_human_task ADD COLUMN assignment_depth integer NOT NULL DEFAULT 0;
ALTER TABLE fw_wf_human_task ADD CONSTRAINT chk_fw_wf_human_assignment
    CHECK ((claimed_by_type IS NULL) = (claimed_by_id IS NULL) AND
        (active_delegate_type IS NULL) = (active_delegate_id IS NULL) AND assignment_depth >= 0);

CREATE TABLE fw_wf_human_collaboration_event (
    id varbinary(512) NOT NULL,
    tenant_id varbinary(512) NOT NULL,
    instance_id varbinary(512) NOT NULL,
    work_item_id varbinary(512) NOT NULL,
    action_code varchar(64) NOT NULL,
    actor_type varchar(64) NOT NULL,
    actor_id varbinary(512) NOT NULL,
    target_type varchar(64), target_id varbinary(512),
    owner_before_type varchar(64), owner_before_id varbinary(512),
    owner_after_type varchar(64), owner_after_id varbinary(512),
    delegate_before_type varchar(64), delegate_before_id varbinary(512),
    delegate_after_type varchar(64), delegate_after_id varbinary(512),
    authorization_receipt_digest varchar(64) NOT NULL,
    execution_nonce varbinary(512) NOT NULL,
    record_digest varchar(64) NOT NULL,
    occurred_time bigint NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, work_item_id, execution_nonce),
    CHECK ((target_type IS NULL) = (target_id IS NULL)),
    CHECK ((owner_before_type IS NULL) = (owner_before_id IS NULL)),
    CHECK ((owner_after_type IS NULL) = (owner_after_id IS NULL)),
    CHECK ((delegate_before_type IS NULL) = (delegate_before_id IS NULL)),
    CHECK ((delegate_after_type IS NULL) = (delegate_after_id IS NULL))
);
CREATE INDEX idx_fw_wf_human_collaboration_task
    ON fw_wf_human_collaboration_event(tenant_id, work_item_id, occurred_time, id);
