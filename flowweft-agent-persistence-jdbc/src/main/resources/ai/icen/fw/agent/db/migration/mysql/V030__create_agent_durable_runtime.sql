-- FlowWeft Agent generic durable runtime V030. Independent history starts from baseline 29.
CREATE TABLE fw_agent_run (
    id char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id varchar(512) NOT NULL,
    tenant_key_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id varchar(512) NOT NULL,
    principal_type varchar(128) NOT NULL,
    principal_id varchar(512) NOT NULL,
    capability_id varchar(512) NOT NULL,
    idempotency_scope_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_replay_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    admission_binding_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    admission_decision_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_status varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state_version bigint NOT NULL,
    event_sequence bigint NOT NULL,
    checkpoint_sequence bigint NOT NULL,
    deadline_time bigint NOT NULL,
    budget_input_tokens bigint NOT NULL,
    budget_output_tokens bigint NOT NULL,
    budget_model_calls integer NOT NULL,
    budget_tool_calls integer NOT NULL,
    budget_duration_millis bigint NOT NULL,
    budget_cost_micros bigint NOT NULL,
    usage_input_tokens bigint NOT NULL,
    usage_output_tokens bigint NOT NULL,
    usage_model_calls integer NOT NULL,
    usage_tool_calls integer NOT NULL,
    usage_duration_millis bigint NOT NULL,
    usage_cost_micros bigint NOT NULL,
    lease_id varchar(512),
    lease_owner_id varchar(512),
    fencing_token bigint,
    lease_acquired_time bigint,
    lease_expires_time bigint,
    last_fencing_token bigint NOT NULL DEFAULT 0,
    current_operation_id varchar(512),
    current_operation_attempt integer,
    current_operation_kind varchar(16) CHARACTER SET ascii COLLATE ascii_bin,
    current_operation_phase varchar(64) CHARACTER SET ascii COLLATE ascii_bin,
    current_operation_digest char(64) CHARACTER SET ascii COLLATE ascii_bin,
    current_checkpoint_id varchar(512),
    state_memento_schema varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state_memento_format_version integer NOT NULL,
    state_memento_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state_memento_payload mediumblob NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    CONSTRAINT pk_fw_agent_run PRIMARY KEY (id),
    CONSTRAINT uk_fw_agent_run_key UNIQUE (tenant_key_digest, id),
    CONSTRAINT uk_fw_agent_run_scope UNIQUE (idempotency_scope_digest),
    CONSTRAINT ck_fw_agent_run_status CHECK (
        run_status IN ('QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'WAITING_TOOL',
                       'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED')
    ),
    CONSTRAINT ck_fw_agent_run_versions CHECK (
        state_version >= 0 AND event_sequence >= 1 AND checkpoint_sequence >= 0
        AND last_fencing_token >= 0
    ),
    CONSTRAINT ck_fw_agent_run_budget CHECK (
        budget_input_tokens > 0 AND budget_output_tokens > 0 AND budget_model_calls > 0
        AND budget_tool_calls >= 0 AND budget_duration_millis > 0 AND budget_cost_micros >= 0
        AND usage_input_tokens >= 0 AND usage_output_tokens >= 0 AND usage_model_calls >= 0
        AND usage_tool_calls >= 0 AND usage_duration_millis >= 0 AND usage_cost_micros >= 0
        AND usage_input_tokens <= budget_input_tokens AND usage_output_tokens <= budget_output_tokens
        AND usage_model_calls <= budget_model_calls AND usage_tool_calls <= budget_tool_calls
        AND usage_duration_millis <= budget_duration_millis AND usage_cost_micros <= budget_cost_micros
    ),
    CONSTRAINT ck_fw_agent_run_time CHECK (
        created_time >= 0 AND updated_time >= created_time AND deadline_time > created_time
    ),
    CONSTRAINT ck_fw_agent_run_lease CHECK (
        (lease_id IS NULL AND lease_owner_id IS NULL AND fencing_token IS NULL
         AND lease_acquired_time IS NULL AND lease_expires_time IS NULL)
        OR
        (lease_id IS NOT NULL AND lease_owner_id IS NOT NULL AND fencing_token IS NOT NULL
         AND lease_acquired_time IS NOT NULL AND lease_expires_time IS NOT NULL
         AND fencing_token > 0 AND fencing_token <= last_fencing_token
         AND lease_acquired_time >= created_time AND updated_time >= lease_acquired_time
         AND lease_expires_time > updated_time AND lease_expires_time <= deadline_time)
    ),
    CONSTRAINT ck_fw_agent_run_operation CHECK (
        (current_operation_id IS NULL AND current_operation_attempt IS NULL
         AND current_operation_kind IS NULL AND current_operation_phase IS NULL
         AND current_operation_digest IS NULL AND current_checkpoint_id IS NULL)
        OR
        (current_operation_id IS NOT NULL AND current_operation_attempt BETWEEN 1 AND 100
         AND current_operation_kind IN ('MODEL', 'TOOL') AND current_operation_phase IS NOT NULL
         AND current_operation_digest IS NOT NULL AND current_checkpoint_id IS NOT NULL)
    ),
    CONSTRAINT ck_fw_agent_run_memento CHECK (
        state_memento_schema = 'agent-durable-run'
        AND state_memento_format_version IN (1, 2)
    ),
    INDEX idx_fw_agent_run_recoverable
        (run_status, lease_expires_time, updated_time, tenant_key_digest, id),
    INDEX idx_fw_agent_run_tenant (tenant_key_digest, updated_time, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE fw_agent_idempotency (
    id char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id varchar(512) NOT NULL,
    tenant_key_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    principal_type varchar(128) NOT NULL,
    principal_id varchar(512) NOT NULL,
    capability_id varchar(512) NOT NULL,
    idempotency_key_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_replay_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_record_id char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id varchar(512) NOT NULL,
    admission_binding_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    admission_decision_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    CONSTRAINT pk_fw_agent_idempotency PRIMARY KEY (id),
    CONSTRAINT ck_fw_agent_idempotency_time CHECK (
        created_time >= 0 AND updated_time >= created_time
    ),
    INDEX idx_fw_agent_idempotency_run (tenant_key_digest, run_record_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE fw_agent_event (
    id char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id varchar(512) NOT NULL,
    tenant_key_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_record_id char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id varchar(512) NOT NULL,
    event_sequence bigint NOT NULL,
    event_type varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_time bigint NOT NULL,
    event_memento_schema varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_memento_format_version integer NOT NULL,
    event_memento_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_memento_payload mediumblob NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    CONSTRAINT pk_fw_agent_event PRIMARY KEY (id),
    CONSTRAINT uk_fw_agent_event_sequence UNIQUE (run_record_id, event_sequence),
    CONSTRAINT ck_fw_agent_event_sequence CHECK (event_sequence > 0),
    CONSTRAINT ck_fw_agent_event_time CHECK (
        occurred_time >= 0 AND created_time >= 0 AND updated_time >= created_time
    ),
    CONSTRAINT ck_fw_agent_event_memento CHECK (
        event_memento_schema = 'agent-run-event'
        AND event_memento_format_version IN (1, 2)
    ),
    INDEX idx_fw_agent_event_tenant_run (tenant_key_digest, run_record_id, event_sequence)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE fw_agent_operation (
    id char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id varchar(512) NOT NULL,
    tenant_key_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_record_id char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id varchar(512) NOT NULL,
    operation_id varchar(512) NOT NULL,
    step_id varchar(512) NOT NULL,
    attempt_count integer NOT NULL,
    operation_kind varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_phase varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_outcome varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    logical_operation_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checkpoint_id varchar(512) NOT NULL,
    claimed_lease_id varchar(512),
    provider_id varchar(512) NOT NULL,
    target_id varchar(512) NOT NULL,
    request_id varchar(512),
    invocation_id varchar(512),
    execution_context_id varchar(512),
    execution_context_receipt_id varchar(512),
    execution_context_receipt_status varchar(32) CHARACTER SET ascii COLLATE ascii_bin,
    dispatch_fence_id varchar(512),
    dispatch_fence_binding_digest char(64) CHARACTER SET ascii COLLATE ascii_bin,
    dispatch_receipt_id varchar(512),
    dispatch_receipt_status varchar(32) CHARACTER SET ascii COLLATE ascii_bin,
    dispatch_provider_revision varchar(512),
    dispatch_consumed_time bigint,
    dispatched_time bigint,
    reserved_cost_micros bigint,
    reserved_duration_millis bigint,
    reconciliation_evidence_digest char(64) CHARACTER SET ascii COLLATE ascii_bin,
    outcome_time bigint,
    outcome_binding_digest char(64) CHARACTER SET ascii COLLATE ascii_bin,
    operation_memento_schema varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_memento_format_version integer NOT NULL,
    operation_memento_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_memento_payload mediumblob NOT NULL,
    evidence_updated_time bigint NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    CONSTRAINT pk_fw_agent_operation PRIMARY KEY (id),
    CONSTRAINT uk_fw_agent_operation_attempt UNIQUE (run_record_id, operation_id, attempt_count),
    CONSTRAINT ck_fw_agent_operation_kind CHECK (operation_kind IN ('MODEL', 'TOOL')),
    CONSTRAINT ck_fw_agent_operation_outcome CHECK (
        operation_outcome IN ('PENDING', 'RECONCILIATION_REQUIRED', 'COMPLETED', 'FAILED', 'SUPERSEDED')
        AND (
            (operation_outcome IN ('PENDING', 'RECONCILIATION_REQUIRED')
             AND outcome_time IS NULL AND outcome_binding_digest IS NULL)
            OR
            (operation_outcome IN ('COMPLETED', 'FAILED', 'SUPERSEDED')
             AND outcome_time IS NOT NULL AND outcome_binding_digest IS NOT NULL)
        )
    ),
    CONSTRAINT ck_fw_agent_operation_attempt CHECK (attempt_count BETWEEN 1 AND 100),
    CONSTRAINT ck_fw_agent_operation_time CHECK (
        created_time >= 0 AND evidence_updated_time >= created_time
        AND updated_time >= evidence_updated_time
        AND (outcome_time IS NULL OR outcome_time >= evidence_updated_time)
    ),
    CONSTRAINT ck_fw_agent_operation_receipts CHECK (
        ((execution_context_receipt_id IS NULL AND execution_context_receipt_status IS NULL)
         OR (execution_context_receipt_id IS NOT NULL AND execution_context_receipt_status IS NOT NULL))
        AND
        ((dispatch_fence_id IS NULL AND dispatch_fence_binding_digest IS NULL)
         OR (dispatch_fence_id IS NOT NULL AND dispatch_fence_binding_digest IS NOT NULL))
        AND
        ((dispatch_receipt_id IS NULL AND dispatch_receipt_status IS NULL
          AND dispatch_provider_revision IS NULL AND dispatch_consumed_time IS NULL)
         OR (dispatch_receipt_id IS NOT NULL AND dispatch_receipt_status IS NOT NULL
          AND dispatch_provider_revision IS NOT NULL AND dispatch_consumed_time IS NOT NULL
          AND dispatch_fence_id IS NOT NULL))
        AND
        ((dispatched_time IS NULL AND reserved_cost_micros IS NULL AND reserved_duration_millis IS NULL)
         OR (dispatched_time IS NOT NULL AND reserved_cost_micros >= 0 AND reserved_duration_millis > 0))
    ),
    CONSTRAINT ck_fw_agent_operation_memento CHECK (
        operation_memento_schema = 'agent-pending-operation'
        AND operation_memento_format_version = 2
    ),
    INDEX idx_fw_agent_operation_tenant_run
        (tenant_key_digest, run_record_id, updated_time, id),
    INDEX idx_fw_agent_operation_reconcile
        (operation_outcome, updated_time, tenant_key_digest, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
