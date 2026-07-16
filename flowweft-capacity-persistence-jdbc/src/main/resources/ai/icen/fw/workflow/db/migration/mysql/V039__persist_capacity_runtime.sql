CREATE TABLE fw_capacity_policy (
    id VARCHAR(512) NOT NULL,
    tenant_id VARCHAR(512) NOT NULL,
    policy_id VARCHAR(512) NOT NULL,
    contract_version VARCHAR(128) NOT NULL,
    revision VARCHAR(512) NOT NULL,
    state_version BIGINT NOT NULL,
    scope_level VARCHAR(32) NOT NULL,
    scope_tenant_id VARCHAR(512),
    scope_provider_id VARCHAR(512),
    resource_type VARCHAR(128),
    resource_id VARCHAR(512),
    effective_time BIGINT NOT NULL,
    expires_time BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    binding_digest CHAR(64) NOT NULL,
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE fw_capacity_policy_workload (
    id CHAR(64) NOT NULL,
    tenant_id VARCHAR(512) NOT NULL,
    policy_row_id VARCHAR(512) NOT NULL,
    workload_kind VARCHAR(128) NOT NULL,
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fw_capacity_policy_workload FOREIGN KEY (policy_row_id)
        REFERENCES fw_capacity_policy (id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE fw_capacity_policy_limit (
    id CHAR(64) NOT NULL,
    tenant_id VARCHAR(512) NOT NULL,
    policy_row_id VARCHAR(512) NOT NULL,
    dimension_code VARCHAR(128) NOT NULL,
    unit_code VARCHAR(128) NOT NULL,
    limit_value BIGINT NOT NULL,
    warning_watermark BIGINT NOT NULL,
    critical_watermark BIGINT NOT NULL,
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fw_capacity_policy_limit FOREIGN KEY (policy_row_id)
        REFERENCES fw_capacity_policy (id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE fw_capacity_policy_degradation (
    id CHAR(64) NOT NULL,
    tenant_id VARCHAR(512) NOT NULL,
    policy_row_id VARCHAR(512) NOT NULL,
    capability_code VARCHAR(128) NOT NULL,
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fw_capacity_policy_degradation FOREIGN KEY (policy_row_id)
        REFERENCES fw_capacity_policy (id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE fw_capacity_policy_snapshot (
    id CHAR(64) NOT NULL,
    tenant_id VARCHAR(512) NOT NULL,
    provider_id VARCHAR(512) NOT NULL,
    target_digest CHAR(64) NOT NULL,
    workload_kind VARCHAR(128) NOT NULL,
    resolution_digest CHAR(64) NOT NULL,
    source_revision_digest CHAR(64) NOT NULL,
    resolution_memento LONGBLOB NOT NULL,
    observed_time BIGINT NOT NULL,
    expires_time BIGINT NOT NULL,
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE fw_capacity_state (
    id CHAR(64) NOT NULL,
    tenant_id VARCHAR(512) NOT NULL,
    provider_id VARCHAR(512) NOT NULL,
    target_digest CHAR(64) NOT NULL,
    workload_kind VARCHAR(128) NOT NULL,
    state_version BIGINT NOT NULL,
    next_fencing_token BIGINT NOT NULL,
    policy_resolution_digest CHAR(64),
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE fw_capacity_measure (
    id CHAR(64) NOT NULL,
    tenant_id VARCHAR(512) NOT NULL,
    state_id CHAR(64) NOT NULL,
    dimension_code VARCHAR(128) NOT NULL,
    unit_code VARCHAR(128) NOT NULL,
    used_value BIGINT NOT NULL,
    reserved_value BIGINT NOT NULL,
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fw_capacity_measure_state FOREIGN KEY (state_id)
        REFERENCES fw_capacity_state (id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE fw_capacity_reservation (
    id VARCHAR(512) NOT NULL,
    tenant_id VARCHAR(512) NOT NULL,
    state_id CHAR(64) NOT NULL,
    lease_id VARCHAR(512) NOT NULL,
    provider_id VARCHAR(512) NOT NULL,
    target_digest CHAR(64) NOT NULL,
    workload_kind VARCHAR(128) NOT NULL,
    lease_digest CHAR(64) NOT NULL,
    fencing_token BIGINT NOT NULL,
    state_version BIGINT NOT NULL,
    policy_resolution_digest CHAR(64) NOT NULL,
    lease_memento LONGBLOB NOT NULL,
    lease_memento_digest CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    acquired_time BIGINT NOT NULL,
    updated_lease_time BIGINT NOT NULL,
    expires_time BIGINT NOT NULL,
    released_time BIGINT,
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE fw_capacity_idempotency (
    id CHAR(64) NOT NULL,
    tenant_id VARCHAR(512) NOT NULL,
    provider_id VARCHAR(512) NOT NULL,
    operation_code VARCHAR(64) NOT NULL,
    scope_digest CHAR(64) NOT NULL,
    binding_digest CHAR(64) NOT NULL,
    request_binding_digest CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    outcome_kind VARCHAR(32),
    outcome_memento LONGBLOB,
    outcome_memento_digest CHAR(64),
    outcome_digest CHAR(64),
    prepared_time BIGINT NOT NULL,
    completed_time BIGINT,
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE fw_capacity_outbox (
    id VARCHAR(512) NOT NULL,
    tenant_id VARCHAR(512) NOT NULL,
    provider_id VARCHAR(512) NOT NULL,
    operation_code VARCHAR(64) NOT NULL,
    aggregate_digest CHAR(64) NOT NULL,
    event_code VARCHAR(128) NOT NULL,
    evidence_digest CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count BIGINT NOT NULL,
    available_time BIGINT NOT NULL,
    published_time BIGINT,
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE INDEX idx_fw_capacity_policy_active ON fw_capacity_policy (tenant_id, enabled, effective_time, expires_time);
CREATE INDEX idx_fw_capacity_reservation_active ON fw_capacity_reservation (tenant_id, state_id, status, expires_time);
CREATE INDEX idx_fw_capacity_outbox_ready ON fw_capacity_outbox (tenant_id, status, available_time);
