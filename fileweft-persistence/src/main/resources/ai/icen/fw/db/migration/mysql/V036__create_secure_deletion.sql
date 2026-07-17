CREATE TABLE fw_secure_deletion_plan
(
    id                       varchar(64)   NOT NULL,
    tenant_id                varchar(64)   NOT NULL,
    create_token             varchar(64)   NOT NULL,
    dispatch_event_id        varchar(64)   NOT NULL,
    tombstone_id             varchar(64)   NOT NULL,
    decision_evidence_id     varchar(64)   NOT NULL,
    resource_type            varchar(128)  NOT NULL,
    resource_id              varchar(64)   NOT NULL,
    resource_revision        bigint        NOT NULL,
    requested_by             varchar(64)   NOT NULL,
    policy_revision          varchar(256)  NOT NULL,
    legal_hold_revision      varchar(256)  NOT NULL,
    authorization_revision   varchar(256)  NOT NULL,
    index_idempotency_key    varchar(1024) NOT NULL,
    object_idempotency_key   varchar(1024) NOT NULL,
    current_stage            varchar(64)   NOT NULL,
    execution_status         varchar(32)   NOT NULL,
    failure_count            integer       NOT NULL DEFAULT 0,
    last_error               varchar(1024),
    created_time             bigint        NOT NULL,
    updated_time             bigint        NOT NULL,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT uq_fw_sec_del_plan_dispatch UNIQUE (tenant_id, dispatch_event_id),
    CONSTRAINT uq_fw_sec_del_plan_tombstone UNIQUE (tenant_id, tombstone_id),
    CONSTRAINT ck_fw_sec_del_plan_revision CHECK (resource_revision >= 0),
    CONSTRAINT ck_fw_sec_del_plan_failures CHECK (failure_count >= 0),
    CONSTRAINT ck_fw_sec_del_plan_times CHECK (created_time >= 0 AND updated_time >= created_time),
    CONSTRAINT ck_fw_sec_del_plan_keys CHECK (index_idempotency_key <> object_idempotency_key),
    CONSTRAINT ck_fw_sec_del_plan_stage CHECK (
        current_stage IN ('PURGE_INDEX_PROJECTIONS', 'PURGE_OBJECT_STORAGE', 'FINALIZE_DATABASE', 'APPEND_COMPLETION_AUDIT')
    ),
    CONSTRAINT ck_fw_sec_del_plan_status CHECK (
        execution_status IN ('PENDING', 'RECONCILING', 'RETRY', 'SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT ck_fw_sec_del_plan_terminal CHECK (
        (execution_status = 'SUCCEEDED' AND current_stage = 'APPEND_COMPLETION_AUDIT' AND last_error IS NULL)
        OR (execution_status <> 'SUCCEEDED' AND current_stage <> 'APPEND_COMPLETION_AUDIT')
    ),
    CONSTRAINT ck_fw_sec_del_plan_failure_shape CHECK (
        (execution_status IN ('RETRY', 'FAILED') AND failure_count > 0 AND last_error IS NOT NULL)
        OR execution_status NOT IN ('RETRY', 'FAILED')
    ),
    CONSTRAINT ck_fw_sec_del_plan_error CHECK (last_error IS NULL OR char_length(trim(last_error)) BETWEEN 1 AND 1024),
    INDEX idx_fw_sec_del_plan_status (tenant_id, execution_status, updated_time, id),
    INDEX idx_fw_sec_del_plan_resource (tenant_id, resource_type, resource_id, resource_revision)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE fw_secure_deletion_tombstone
(
    id                       varchar(64)  NOT NULL,
    tenant_id                varchar(64)  NOT NULL,
    plan_id                  varchar(64)  NOT NULL,
    resource_type            varchar(128) NOT NULL,
    resource_id              varchar(64)  NOT NULL,
    resource_revision        bigint       NOT NULL,
    blocked_time             bigint       NOT NULL,
    policy_revision          varchar(256) NOT NULL,
    legal_hold_revision      varchar(256) NOT NULL,
    authorization_revision   varchar(256) NOT NULL,
    created_time             bigint       NOT NULL,
    updated_time             bigint       NOT NULL,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT uq_fw_sec_del_tomb_plan UNIQUE (tenant_id, plan_id),
    CONSTRAINT uq_fw_sec_del_tomb_resource UNIQUE (tenant_id, resource_type, resource_id),
    CONSTRAINT fk_fw_sec_del_tomb_plan FOREIGN KEY (tenant_id, plan_id)
        REFERENCES fw_secure_deletion_plan(tenant_id, id),
    CONSTRAINT ck_fw_sec_del_tomb_revision CHECK (resource_revision >= 0),
    CONSTRAINT ck_fw_sec_del_tomb_times CHECK (
        blocked_time >= 0 AND created_time = blocked_time AND updated_time >= created_time
    ),
    INDEX idx_fw_sec_del_tomb_revision (tenant_id, resource_type, resource_id, resource_revision)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE fw_secure_deletion_audit
(
    id                           varchar(64)   NOT NULL,
    tenant_id                    varchar(64)   NOT NULL,
    create_token                 varchar(64)   NOT NULL,
    evidence_type                varchar(32)   NOT NULL,
    plan_id                      varchar(64),
    tombstone_id                 varchar(64),
    resource_type                varchar(128)  NOT NULL,
    resource_id                  varchar(64)   NOT NULL,
    resource_revision            bigint        NOT NULL,
    requested_by                 varchar(64)   NOT NULL,
    occurred_time                bigint        NOT NULL,
    decision_reason              varchar(64),
    policy_revision              varchar(256),
    legal_hold_revision          varchar(256),
    authorization_revision       varchar(256),
    active_legal_hold_ids_json   json          NOT NULL,
    failed_stage                 varchar(64),
    failure_count                integer,
    message                      varchar(1024),
    created_time                 bigint        NOT NULL,
    updated_time                 bigint        NOT NULL,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT fk_fw_sec_del_audit_plan FOREIGN KEY (tenant_id, plan_id)
        REFERENCES fw_secure_deletion_plan(tenant_id, id),
    CONSTRAINT fk_fw_sec_del_audit_tomb FOREIGN KEY (tenant_id, tombstone_id)
        REFERENCES fw_secure_deletion_tombstone(tenant_id, id),
    CONSTRAINT ck_fw_sec_del_audit_revision CHECK (resource_revision >= 0),
    CONSTRAINT ck_fw_sec_del_audit_times CHECK (
        occurred_time >= 0 AND created_time = occurred_time AND updated_time >= created_time
    ),
    CONSTRAINT ck_fw_sec_del_audit_type CHECK (evidence_type IN ('DECISION', 'COMPLETION', 'FAILURE')),
    CONSTRAINT ck_fw_sec_del_audit_reason CHECK (
        decision_reason IS NULL OR decision_reason IN (
            'ALLOWED', 'ACTIVE_LEGAL_HOLD', 'INCOMPLETE_LEGAL_HOLD_EVIDENCE',
            'INCOMPLETE_RETENTION_POLICY', 'RETAIN_INDEFINITELY', 'RETENTION_PERIOD_ACTIVE',
            'INCOMPLETE_AUTHORIZATION_EVIDENCE', 'AUTHORIZATION_DENIED', 'AUTHORIZATION_EXPIRED',
            'LEGAL_HOLD_EVIDENCE_EXPIRED', 'RETENTION_POLICY_EVIDENCE_EXPIRED', 'EVIDENCE_FROM_FUTURE'
        )
    ),
    CONSTRAINT ck_fw_sec_del_audit_failed_stage CHECK (
        failed_stage IS NULL OR failed_stage IN ('PURGE_INDEX_PROJECTIONS', 'PURGE_OBJECT_STORAGE', 'FINALIZE_DATABASE')
    ),
    CONSTRAINT ck_fw_sec_del_audit_shape CHECK (
        (
            evidence_type = 'DECISION'
            AND decision_reason IS NOT NULL
            AND policy_revision IS NOT NULL
            AND legal_hold_revision IS NOT NULL
            AND authorization_revision IS NOT NULL
            AND failed_stage IS NULL AND failure_count IS NULL AND message IS NULL
            AND (
                (decision_reason = 'ALLOWED' AND plan_id IS NOT NULL AND tombstone_id IS NOT NULL)
                OR (decision_reason <> 'ALLOWED' AND plan_id IS NULL AND tombstone_id IS NULL)
            )
        )
        OR (
            evidence_type = 'COMPLETION'
            AND plan_id IS NOT NULL AND tombstone_id IS NOT NULL
            AND decision_reason IS NULL
            AND policy_revision IS NULL AND legal_hold_revision IS NULL AND authorization_revision IS NULL
            AND failed_stage IS NULL AND failure_count IS NULL AND message IS NULL
        )
        OR (
            evidence_type = 'FAILURE'
            AND plan_id IS NOT NULL AND tombstone_id IS NOT NULL
            AND decision_reason IS NULL
            AND policy_revision IS NULL AND legal_hold_revision IS NULL AND authorization_revision IS NULL
            AND failed_stage IS NOT NULL AND failure_count IS NOT NULL AND failure_count > 0
            AND message IS NOT NULL
            AND char_length(trim(message)) BETWEEN 1 AND 1024
        )
    ),
    INDEX idx_fw_sec_del_audit_resource (tenant_id, resource_type, resource_id, occurred_time DESC),
    INDEX idx_fw_sec_del_audit_plan (tenant_id, plan_id, evidence_type)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE fw_secure_deletion_receipt
(
    tenant_id              varchar(64)   NOT NULL,
    plan_id                varchar(64)   NOT NULL,
    deletion_stage         varchar(64)   NOT NULL,
    idempotency_key        varchar(1024) NOT NULL,
    provider_id            varchar(128)  NOT NULL,
    provider_target        varchar(32)   NOT NULL,
    provider_status        varchar(32)   NOT NULL,
    request_binding_digest varchar(64)   NOT NULL,
    receipt_reference      varchar(2048),
    message                varchar(1024),
    evidence_json          json          NOT NULL,
    recorded_time          bigint        NOT NULL,
    created_time           bigint        NOT NULL,
    updated_time           bigint        NOT NULL,
    PRIMARY KEY (tenant_id, plan_id, deletion_stage),
    CONSTRAINT fk_fw_sec_del_receipt_plan FOREIGN KEY (tenant_id, plan_id)
        REFERENCES fw_secure_deletion_plan(tenant_id, id),
    CONSTRAINT ck_fw_sec_del_receipt_stage CHECK (
        deletion_stage IN ('PURGE_INDEX_PROJECTIONS', 'PURGE_OBJECT_STORAGE')
    ),
    CONSTRAINT ck_fw_sec_del_receipt_target CHECK (provider_target IN ('INDEX', 'OBJECT_STORAGE')),
    CONSTRAINT ck_fw_sec_del_receipt_stage_target CHECK (
        (deletion_stage = 'PURGE_INDEX_PROJECTIONS' AND provider_target = 'INDEX')
        OR (deletion_stage = 'PURGE_OBJECT_STORAGE' AND provider_target = 'OBJECT_STORAGE')
    ),
    CONSTRAINT ck_fw_sec_del_receipt_status CHECK (
        provider_status IN ('VERIFIED_ABSENT', 'ACCEPTED_UNVERIFIED', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE')
    ),
    CONSTRAINT ck_fw_sec_del_receipt_binding CHECK (
        char_length(request_binding_digest) = 64 AND request_binding_digest = lower(request_binding_digest)
    ),
    CONSTRAINT ck_fw_sec_del_receipt_reference CHECK (
        provider_status IN ('RETRYABLE_FAILURE', 'PERMANENT_FAILURE') OR receipt_reference IS NOT NULL
    ),
    CONSTRAINT ck_fw_sec_del_receipt_message CHECK (message IS NULL OR char_length(trim(message)) BETWEEN 1 AND 1024),
    CONSTRAINT ck_fw_sec_del_receipt_times CHECK (
        recorded_time >= 0 AND created_time >= 0 AND updated_time >= created_time AND recorded_time = updated_time
    ),
    INDEX idx_fw_sec_del_receipt_status (tenant_id, provider_status, updated_time, plan_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
