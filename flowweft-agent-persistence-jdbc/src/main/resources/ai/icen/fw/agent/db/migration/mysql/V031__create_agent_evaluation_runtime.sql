-- FlowWeft Agent evaluation persistence V031. V030 is reserved for the generic durable runtime.
CREATE TABLE fw_agent_evaluation_run (
    id varchar(256) NOT NULL,
    tenant_id varchar(256) NOT NULL,
    request_id varchar(256) NOT NULL,
    principal_type varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    principal_id varchar(256) NOT NULL,
    authorization_revision varchar(512) NOT NULL,
    suite_id varchar(256) NOT NULL,
    suite_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_snapshot_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_binding_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_scope_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_status varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state_version bigint NOT NULL,
    attempt_count integer NOT NULL,
    deadline_time bigint NOT NULL,
    maximum_attempts integer NOT NULL,
    lease_id varchar(256),
    lease_owner_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin,
    fencing_token bigint,
    last_fencing_token bigint NOT NULL DEFAULT 0,
    lease_acquired_time bigint,
    lease_expires_time bigint,
    memento_schema varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    memento_format_version integer NOT NULL,
    memento_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    memento_payload mediumblob NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    CONSTRAINT pk_fw_agent_evaluation_run PRIMARY KEY (tenant_id, id),
    CONSTRAINT uk_fw_agent_evaluation_run_scope UNIQUE (tenant_id, idempotency_scope_digest),
    CONSTRAINT ck_fw_agent_evaluation_run_status CHECK (
        run_status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED')
    ),
    CONSTRAINT ck_fw_agent_evaluation_run_version CHECK (
        state_version >= 0 AND attempt_count >= 0 AND maximum_attempts BETWEEN 1 AND 100
        AND attempt_count <= maximum_attempts AND last_fencing_token >= 0
    ),
    CONSTRAINT ck_fw_agent_evaluation_run_time CHECK (
        created_time >= 0 AND updated_time >= created_time AND deadline_time > created_time
    ),
    CONSTRAINT ck_fw_agent_evaluation_run_schema CHECK (
        memento_schema = 'agent-evaluation-run' AND memento_format_version = 1
    ),
    CONSTRAINT ck_fw_agent_evaluation_run_lease CHECK (
        (
            lease_id IS NULL AND lease_owner_id IS NULL AND fencing_token IS NULL
            AND lease_acquired_time IS NULL AND lease_expires_time IS NULL
            AND run_status <> 'RUNNING'
        ) OR (
            lease_id IS NOT NULL AND lease_owner_id IS NOT NULL AND fencing_token IS NOT NULL
            AND fencing_token > 0 AND fencing_token <= last_fencing_token
            AND lease_acquired_time IS NOT NULL AND lease_expires_time IS NOT NULL
            AND lease_acquired_time >= created_time AND updated_time >= lease_acquired_time
            AND lease_expires_time > updated_time AND lease_expires_time <= deadline_time
            AND run_status = 'RUNNING'
        )
    ),
    INDEX idx_fw_agent_evaluation_recoverable (
        run_status, lease_expires_time, updated_time, tenant_id, id
    ),
    INDEX idx_fw_agent_evaluation_suite (tenant_id, suite_id, created_time)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE fw_agent_evaluation_idempotency (
    id char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id varchar(256) NOT NULL,
    scope_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    principal_type varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    principal_id varchar(256) NOT NULL,
    authorization_revision varchar(512) NOT NULL,
    suite_id varchar(256) NOT NULL,
    suite_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_snapshot_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evaluation_id varchar(256) NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    CONSTRAINT pk_fw_agent_evaluation_idempotency PRIMARY KEY (tenant_id, id),
    CONSTRAINT uk_fw_agent_evaluation_idempotency_scope UNIQUE (tenant_id, scope_digest),
    CONSTRAINT ck_fw_agent_evaluation_idempotency_time CHECK (
        created_time >= 0 AND updated_time >= created_time
    ),
    INDEX idx_fw_agent_evaluation_idempotency_run (tenant_id, evaluation_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
