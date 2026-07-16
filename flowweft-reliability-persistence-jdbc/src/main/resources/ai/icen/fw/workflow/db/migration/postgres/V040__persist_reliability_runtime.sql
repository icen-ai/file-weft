-- FlowWeft reliability runtime: PostgreSQL V040
CREATE TABLE fw_reliability_run (
    id VARCHAR(512) NOT NULL,
    tenant_id VARCHAR(512) NOT NULL,
    idempotency_digest CHAR(64) NOT NULL,
    operation_kind VARCHAR(32) NOT NULL,
    intent_digest CHAR(64) NOT NULL,
    argument_digest CHAR(64) NOT NULL,
    provider_id VARCHAR(128) NOT NULL,
    provider_revision VARCHAR(256) NOT NULL,
    provider_descriptor_digest CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    state_version BIGINT NOT NULL,
    state_digest CHAR(64) NOT NULL,
    state_memento BYTEA NOT NULL,
    state_memento_digest CHAR(64) NOT NULL,
    lease_owner_id VARCHAR(512),
    lease_fencing_token BIGINT,
    lease_expires_time BIGINT,
    next_fencing_token BIGINT NOT NULL,
    provider_operation_id VARCHAR(512),
    original_attempt_digest CHAR(64),
    outcome_unknown_digest CHAR(64),
    outcome_evidence_digest CHAR(64),
    execution_deadline_time BIGINT NOT NULL,
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    CONSTRAINT chk_fw_reliability_run_memento_size
        CHECK (OCTET_LENGTH(state_memento) BETWEEN 1 AND 8388608),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, idempotency_digest)
);

CREATE TABLE fw_reliability_provider_attempt (
    id VARCHAR(512) NOT NULL,
    tenant_id VARCHAR(512) NOT NULL,
    run_id VARCHAR(512) NOT NULL,
    operation_kind VARCHAR(32) NOT NULL,
    provider_id VARCHAR(128) NOT NULL,
    provider_revision VARCHAR(256) NOT NULL,
    provider_operation_id VARCHAR(512) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    version_fence_digest CHAR(64) NOT NULL,
    attempt_digest CHAR(64) NOT NULL,
    attempt_memento BYTEA NOT NULL,
    attempt_memento_digest CHAR(64) NOT NULL,
    started_time BIGINT NOT NULL,
    deadline_time BIGINT NOT NULL,
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    CONSTRAINT chk_fw_reliability_attempt_memento_size
        CHECK (OCTET_LENGTH(attempt_memento) BETWEEN 1 AND 8388608),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, attempt_digest)
);

CREATE TABLE fw_reliability_provider_receipt (
    id VARCHAR(512) NOT NULL,
    tenant_id VARCHAR(512) NOT NULL,
    run_id VARCHAR(512) NOT NULL,
    attempt_digest CHAR(64),
    evidence_kind VARCHAR(32) NOT NULL,
    evidence_digest CHAR(64) NOT NULL,
    reference_digest CHAR(64),
    evidence_memento BYTEA NOT NULL,
    evidence_memento_digest CHAR(64) NOT NULL,
    recorded_time BIGINT NOT NULL,
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    CONSTRAINT chk_fw_reliability_receipt_memento_size
        CHECK (OCTET_LENGTH(evidence_memento) BETWEEN 1 AND 8388608),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, evidence_kind, evidence_digest)
);

CREATE TABLE fw_reliability_outbox (
    id VARCHAR(512) NOT NULL,
    tenant_id VARCHAR(512) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(512) NOT NULL,
    aggregate_state_digest CHAR(64) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    record_digest CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    lease_owner_id VARCHAR(512),
    lease_fencing_token BIGINT,
    lease_expires_time BIGINT,
    next_fencing_token BIGINT NOT NULL,
    published_time BIGINT,
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE fw_reliability_slo_schedule (
    id VARCHAR(512) NOT NULL,
    tenant_id VARCHAR(512) NOT NULL,
    policy_binding_digest CHAR(64) NOT NULL,
    objective_resource_digest CHAR(64) NOT NULL,
    next_evaluation_time BIGINT NOT NULL,
    cadence_millis BIGINT NOT NULL,
    state_version BIGINT NOT NULL,
    state_digest CHAR(64) NOT NULL,
    state_memento BYTEA NOT NULL,
    state_memento_digest CHAR(64) NOT NULL,
    lease_owner_id VARCHAR(512),
    lease_fencing_token BIGINT,
    lease_expires_time BIGINT,
    next_fencing_token BIGINT NOT NULL,
    last_evaluation_digest CHAR(64),
    last_alert_digest CHAR(64),
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    CONSTRAINT chk_fw_reliability_schedule_memento_size
        CHECK (OCTET_LENGTH(state_memento) BETWEEN 1 AND 8388608),
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE fw_reliability_slo_evaluation (
    id VARCHAR(512) NOT NULL,
    tenant_id VARCHAR(512) NOT NULL,
    schedule_id VARCHAR(512) NOT NULL,
    schedule_version BIGINT NOT NULL,
    evaluation_digest CHAR(64) NOT NULL,
    alert_digest CHAR(64) NOT NULL,
    record_memento BYTEA NOT NULL,
    record_memento_digest CHAR(64) NOT NULL,
    evaluated_time BIGINT NOT NULL,
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    CONSTRAINT chk_fw_reliability_evaluation_memento_size
        CHECK (OCTET_LENGTH(record_memento) BETWEEN 1 AND 8388608),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, evaluation_digest, alert_digest)
);

CREATE INDEX idx_fw_reliability_run_tenant_status ON fw_reliability_run (tenant_id, status, updated_time);
CREATE INDEX idx_fw_reliability_attempt_run ON fw_reliability_provider_attempt (tenant_id, run_id);
CREATE INDEX idx_fw_reliability_receipt_run ON fw_reliability_provider_receipt (tenant_id, run_id);
CREATE INDEX idx_fw_reliability_outbox_ready ON fw_reliability_outbox (status, lease_expires_time, created_time);
CREATE INDEX idx_fw_reliability_slo_due ON fw_reliability_slo_schedule (tenant_id, next_evaluation_time, lease_expires_time);
CREATE INDEX idx_fw_reliability_slo_history
    ON fw_reliability_slo_evaluation (tenant_id, schedule_id, evaluated_time);
