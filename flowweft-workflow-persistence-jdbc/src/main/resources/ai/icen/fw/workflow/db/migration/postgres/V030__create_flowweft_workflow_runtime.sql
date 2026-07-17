-- FlowWeft generic Workflow owns this V030+ migration line. It is independent of V001-V029.
CREATE TABLE fw_wf_definition (
    id varchar(512) NOT NULL,
    tenant_id varchar(512) NOT NULL,
    definition_key varchar(256) NOT NULL,
    title varchar(1024) NOT NULL,
    lifecycle_status varchar(64) NOT NULL,
    latest_version_id varchar(512),
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, definition_key)
);

CREATE TABLE fw_wf_definition_version (
    id varchar(512) NOT NULL,
    tenant_id varchar(512) NOT NULL,
    definition_id varchar(512) NOT NULL,
    definition_key varchar(256) NOT NULL,
    definition_version varchar(128) NOT NULL,
    definition_digest varchar(64) NOT NULL,
    schema_version integer NOT NULL,
    definition_status varchar(64) NOT NULL,
    definition_payload bytea NOT NULL,
    execution_receipt_id varchar(512) NOT NULL,
    capability_digest varchar(64) NOT NULL,
    receipt_accepted_time bigint NOT NULL,
    receipt_valid_until bigint NOT NULL,
    receipt_digest varchar(64) NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, definition_id, definition_version),
    UNIQUE (tenant_id, definition_id, definition_digest),
    CHECK (schema_version > 0),
    CHECK (receipt_valid_until >= receipt_accepted_time)
);

CREATE TABLE fw_wf_instance (
    id varchar(512) NOT NULL,
    tenant_id varchar(512) NOT NULL,
    definition_id varchar(512) NOT NULL,
    definition_key varchar(256) NOT NULL,
    definition_version varchar(128) NOT NULL,
    definition_digest varchar(64) NOT NULL,
    subject_type varchar(64) NOT NULL,
    subject_id varchar(512) NOT NULL,
    subject_revision varchar(256) NOT NULL,
    subject_digest varchar(64) NOT NULL,
    initiator_type varchar(64) NOT NULL,
    initiator_id varchar(512) NOT NULL,
    status varchar(64) NOT NULL,
    instance_version bigint NOT NULL,
    state_digest varchar(64) NOT NULL,
    state_payload bytea NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    CHECK (instance_version > 0),
    CHECK (updated_time >= created_time)
);
CREATE INDEX idx_fw_wf_instance_subject ON fw_wf_instance(tenant_id, subject_type, subject_id, updated_time);
CREATE INDEX idx_fw_wf_instance_status ON fw_wf_instance(tenant_id, status, updated_time);

CREATE TABLE fw_wf_token (
    id varchar(512) NOT NULL,
    tenant_id varchar(512) NOT NULL,
    instance_id varchar(512) NOT NULL,
    node_id varchar(128) NOT NULL,
    token_status varchar(64) NOT NULL,
    token_revision bigint NOT NULL,
    waiting_execution_id varchar(512),
    content_digest varchar(64) NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX idx_fw_wf_token_instance ON fw_wf_token(tenant_id, instance_id, token_status);

CREATE TABLE fw_wf_node_execution (
    id varchar(512) NOT NULL,
    tenant_id varchar(512) NOT NULL,
    instance_id varchar(512) NOT NULL,
    token_id varchar(512) NOT NULL,
    node_id varchar(128) NOT NULL,
    execution_status varchar(64) NOT NULL,
    execution_revision bigint NOT NULL,
    started_time bigint NOT NULL,
    completed_time bigint,
    pending_effect_id varchar(512),
    pending_effect_code varchar(128),
    effect_request_digest varchar(64),
    content_digest varchar(64) NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX idx_fw_wf_execution_instance ON fw_wf_node_execution(tenant_id, instance_id, execution_status);

CREATE TABLE fw_wf_human_task (
    id varchar(512) NOT NULL,
    tenant_id varchar(512) NOT NULL,
    instance_id varchar(512) NOT NULL,
    node_execution_id varchar(512) NOT NULL,
    token_id varchar(512) NOT NULL,
    node_id varchar(128) NOT NULL,
    policy_digest varchar(64) NOT NULL,
    task_status varchar(64) NOT NULL,
    active_rule_index integer NOT NULL,
    task_revision bigint NOT NULL,
    content_digest varchar(64) NOT NULL,
    claimed_by_type varchar(64),
    claimed_by_id varchar(512),
    due_time bigint,
    follow_up_time bigint,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    CHECK (active_rule_index >= 0)
);
CREATE INDEX idx_fw_wf_human_inbox ON fw_wf_human_task(tenant_id, task_status, updated_time);
CREATE INDEX idx_fw_wf_human_instance ON fw_wf_human_task(tenant_id, instance_id, created_time);

CREATE TABLE fw_wf_human_candidate (
    id varchar(512) NOT NULL,
    tenant_id varchar(512) NOT NULL,
    instance_id varchar(512) NOT NULL,
    work_item_id varchar(512) NOT NULL,
    rule_index integer NOT NULL,
    candidate_ordinal integer NOT NULL,
    principal_type varchar(64) NOT NULL,
    principal_id varchar(512) NOT NULL,
    selector_digest varchar(64) NOT NULL,
    resolution_digest varchar(64) NOT NULL,
    activation_receipt_digest varchar(64) NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, work_item_id, rule_index, candidate_ordinal)
);
CREATE INDEX idx_fw_wf_candidate_principal ON fw_wf_human_candidate(tenant_id, principal_type, principal_id, work_item_id);

-- Immutable decision evidence: the JDBC adapter only inserts and verifies this table.
CREATE TABLE fw_wf_human_decision (
    id varchar(512) NOT NULL,
    tenant_id varchar(512) NOT NULL,
    instance_id varchar(512) NOT NULL,
    work_item_id varchar(512) NOT NULL,
    rule_index integer NOT NULL,
    actor_type varchar(64) NOT NULL,
    actor_id varchar(512) NOT NULL,
    decision_code varchar(64) NOT NULL,
    authorization_receipt_digest varchar(64) NOT NULL,
    decision_digest varchar(64) NOT NULL,
    occurred_time bigint NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, work_item_id, rule_index, actor_type, actor_id)
);
CREATE INDEX idx_fw_wf_decision_instance ON fw_wf_human_decision(tenant_id, instance_id, occurred_time);

-- Immutable ordered event log: no UPDATE/DELETE is issued by the owning adapter.
CREATE TABLE fw_wf_event (
    id varchar(512) NOT NULL,
    tenant_id varchar(512) NOT NULL,
    instance_id varchar(512) NOT NULL,
    definition_id varchar(512) NOT NULL,
    definition_key varchar(256) NOT NULL,
    definition_version varchar(128) NOT NULL,
    definition_digest varchar(64) NOT NULL,
    event_code varchar(128) NOT NULL,
    token_id varchar(512),
    node_execution_id varchar(512),
    work_item_id varchar(512),
    node_id varchar(128),
    subject_type varchar(64) NOT NULL,
    subject_id varchar(512) NOT NULL,
    subject_revision varchar(256) NOT NULL,
    subject_digest varchar(64) NOT NULL,
    payload_digest varchar(64) NOT NULL,
    instance_version bigint NOT NULL,
    event_digest varchar(64) NOT NULL,
    occurred_time bigint NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, instance_id, instance_version, id)
);
CREATE INDEX idx_fw_wf_event_instance ON fw_wf_event(tenant_id, instance_id, occurred_time, id);

CREATE TABLE fw_wf_effect (
    id varchar(512) NOT NULL,
    tenant_id varchar(512) NOT NULL,
    instance_id varchar(512) NOT NULL,
    definition_id varchar(512) NOT NULL,
    definition_key varchar(256) NOT NULL,
    definition_version varchar(128) NOT NULL,
    definition_digest varchar(64) NOT NULL,
    subject_type varchar(64) NOT NULL,
    subject_id varchar(512) NOT NULL,
    subject_revision varchar(256) NOT NULL,
    subject_digest varchar(64) NOT NULL,
    token_id varchar(512),
    node_execution_id varchar(512),
    work_item_id varchar(512),
    node_id varchar(128),
    rule_index integer,
    effect_code varchar(128) NOT NULL,
    payload_digest varchar(64) NOT NULL,
    request_digest varchar(64) NOT NULL,
    delivery_status varchar(64) NOT NULL,
    record_version bigint NOT NULL,
    attempt_count integer NOT NULL,
    next_attempt_time bigint,
    lease_id varchar(512),
    worker_id varchar(512),
    fencing_token bigint,
    lease_acquired_time bigint,
    lease_expires_time bigint,
    execution_phase varchar(64),
    checkpoint_sequence bigint NOT NULL,
    checkpoint_digest varchar(64),
    outcome_digest varchar(64),
    retry_reason_digest varchar(64),
    reconciliation_evidence_digest varchar(64),
    acknowledgement_kind varchar(64),
    acknowledgement_receipt_digest varchar(64),
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, request_digest),
    CHECK (record_version >= 0),
    CHECK (attempt_count >= 0),
    CHECK (checkpoint_sequence >= 0)
);
CREATE INDEX idx_fw_wf_effect_ready ON fw_wf_effect(tenant_id, delivery_status, next_attempt_time, created_time);
CREATE INDEX idx_fw_wf_effect_instance ON fw_wf_effect(tenant_id, instance_id, created_time);

CREATE TABLE fw_wf_job (
    id varchar(512) NOT NULL,
    tenant_id varchar(512) NOT NULL,
    instance_id varchar(512) NOT NULL,
    effect_id varchar(512) NOT NULL,
    job_type varchar(128) NOT NULL,
    job_status varchar(64) NOT NULL,
    available_time bigint NOT NULL,
    failure_digest varchar(64),
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, effect_id)
);
CREATE INDEX idx_fw_wf_job_ready ON fw_wf_job(tenant_id, job_status, available_time, created_time);

CREATE TABLE fw_wf_idempotency (
    id varchar(512) NOT NULL,
    tenant_id varchar(512) NOT NULL,
    instance_id varchar(512) NOT NULL,
    idempotency_key varchar(512) NOT NULL,
    logical_request_digest varchar(64) NOT NULL,
    command_code varchar(128) NOT NULL,
    domain_command_digest varchar(64) NOT NULL,
    result_version bigint NOT NULL,
    effect_count integer NOT NULL,
    domain_result_code varchar(64) NOT NULL,
    committed_time bigint NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, instance_id, idempotency_key)
);

CREATE TABLE fw_wf_timer (
    id varchar(512) NOT NULL,
    tenant_id varchar(512) NOT NULL,
    instance_id varchar(512) NOT NULL,
    node_execution_id varchar(512) NOT NULL,
    effect_id varchar(512),
    timer_status varchar(64) NOT NULL,
    due_time bigint NOT NULL,
    schedule_digest varchar(64) NOT NULL,
    fired_time bigint,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX idx_fw_wf_timer_due ON fw_wf_timer(tenant_id, timer_status, due_time, id);

CREATE TABLE fw_wf_subscription (
    id varchar(512) NOT NULL,
    tenant_id varchar(512) NOT NULL,
    instance_id varchar(512) NOT NULL,
    node_execution_id varchar(512) NOT NULL,
    subscription_type varchar(64) NOT NULL,
    correlation_digest varchar(64) NOT NULL,
    subscription_status varchar(64) NOT NULL,
    expires_time bigint,
    consumed_time bigint,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, subscription_type, correlation_digest, id)
);
CREATE INDEX idx_fw_wf_subscription_wait ON fw_wf_subscription(tenant_id, subscription_status, expires_time);

CREATE TABLE fw_wf_incident (
    id varchar(512) NOT NULL,
    tenant_id varchar(512) NOT NULL,
    instance_id varchar(512) NOT NULL,
    effect_id varchar(512),
    node_execution_id varchar(512),
    incident_code varchar(128) NOT NULL,
    incident_status varchar(64) NOT NULL,
    evidence_digest varchar(64) NOT NULL,
    repair_digest varchar(64),
    occurred_time bigint NOT NULL,
    resolved_time bigint,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX idx_fw_wf_incident_open ON fw_wf_incident(tenant_id, incident_status, occurred_time);

CREATE TABLE fw_wf_variable (
    id varchar(512) NOT NULL,
    tenant_id varchar(512) NOT NULL,
    instance_id varchar(512) NOT NULL,
    variable_name varchar(256) NOT NULL,
    classification varchar(64) NOT NULL,
    value_digest varchar(64) NOT NULL,
    value_payload bytea,
    secret_reference varchar(1024),
    variable_version bigint NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, instance_id, variable_name),
    CHECK ((value_payload IS NULL) OR (secret_reference IS NULL))
);

CREATE TABLE fw_wf_lease (
    id varchar(512) NOT NULL,
    tenant_id varchar(512) NOT NULL,
    owner_type varchar(64) NOT NULL,
    owner_id varchar(512) NOT NULL,
    worker_id varchar(512) NOT NULL,
    fencing_token bigint NOT NULL,
    acquired_time bigint NOT NULL,
    expires_time bigint NOT NULL,
    lease_status varchar(64) NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, owner_type, owner_id),
    CHECK (fencing_token > 0),
    CHECK (expires_time > acquired_time)
);
