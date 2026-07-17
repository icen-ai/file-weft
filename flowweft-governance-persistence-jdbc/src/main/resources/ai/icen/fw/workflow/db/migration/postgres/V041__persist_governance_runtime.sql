CREATE TABLE fw_governance_deletion_run
(
    id                      varchar(64) PRIMARY KEY,
    tenant_id               varchar(512) NOT NULL,
    plan_id                 varchar(512) NOT NULL,
    plan_id_digest          varchar(64) NOT NULL,
    idempotency_digest      varchar(64) NOT NULL,
    run_version             bigint NOT NULL,
    status_code             varchar(128) NOT NULL,
    state_digest            varchar(64) NOT NULL,
    memento_version         integer NOT NULL,
    run_memento             bytea NOT NULL,
    run_memento_digest      varchar(64) NOT NULL,
    created_time            bigint NOT NULL,
    updated_time            bigint NOT NULL,
    CONSTRAINT uq_fw_gov_run_plan UNIQUE (tenant_id, plan_id_digest),
    CONSTRAINT uq_fw_gov_run_idempotency UNIQUE (tenant_id, idempotency_digest),
    CONSTRAINT ck_fw_gov_run_version CHECK (run_version > 0),
    CONSTRAINT ck_fw_gov_run_memento_version CHECK (memento_version = 1),
    CONSTRAINT ck_fw_gov_run_memento_size CHECK (OCTET_LENGTH(run_memento) BETWEEN 1 AND 4194304)
);

CREATE INDEX idx_fw_gov_run_tenant_status
    ON fw_governance_deletion_run (tenant_id, status_code, updated_time);

CREATE TABLE fw_governance_deletion_outbox
(
    id                      varchar(64) PRIMARY KEY,
    tenant_id               varchar(512) NOT NULL,
    record_id               varchar(512) NOT NULL,
    record_id_digest        varchar(64) NOT NULL,
    plan_id                 varchar(512) NOT NULL,
    plan_id_digest          varchar(64) NOT NULL,
    event_type              varchar(128) NOT NULL,
    run_version             bigint NOT NULL,
    state_digest            varchar(64) NOT NULL,
    record_digest           varchar(64) NOT NULL,
    memento_version         integer NOT NULL,
    run_memento             bytea NOT NULL,
    run_memento_digest      varchar(64) NOT NULL,
    available_time          bigint NOT NULL,
    claim_digest            varchar(64),
    worker_digest           varchar(64),
    fencing_token           bigint NOT NULL DEFAULT 0,
    lease_expires_time      bigint,
    acknowledged_time       bigint,
    created_time            bigint NOT NULL,
    updated_time            bigint NOT NULL,
    CONSTRAINT uq_fw_gov_outbox_record UNIQUE (tenant_id, record_id_digest),
    CONSTRAINT ck_fw_gov_outbox_version CHECK (run_version > 0),
    CONSTRAINT ck_fw_gov_outbox_fence CHECK (fencing_token >= 0),
    CONSTRAINT ck_fw_gov_outbox_memento_version CHECK (memento_version = 1),
    CONSTRAINT ck_fw_gov_outbox_memento_size CHECK (OCTET_LENGTH(run_memento) BETWEEN 1 AND 4194304)
);

CREATE INDEX idx_fw_gov_outbox_ready
    ON fw_governance_deletion_outbox
       (tenant_id, acknowledged_time, available_time, lease_expires_time);

CREATE INDEX idx_fw_gov_outbox_run
    ON fw_governance_deletion_outbox (tenant_id, plan_id_digest, run_version);
