CREATE TABLE fw_wf_doc_binding (
    id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    document_type VARCHAR(64) NOT NULL,
    document_id VARCHAR(512) NOT NULL,
    instance_id VARCHAR(512) NOT NULL,
    state_code VARCHAR(64) NOT NULL,
    subject_revision VARCHAR(256) NOT NULL,
    subject_digest VARCHAR(64) NOT NULL,
    definition_id VARCHAR(512) NOT NULL,
    definition_key VARCHAR(256) NOT NULL,
    definition_version VARCHAR(128) NOT NULL,
    definition_digest VARCHAR(64) NOT NULL,
    template_key VARCHAR(256) NOT NULL,
    template_revision VARCHAR(256) NOT NULL,
    template_digest VARCHAR(64) NOT NULL,
    revision_policy_revision VARCHAR(256) NOT NULL,
    revision_policy_digest VARCHAR(64) NOT NULL,
    revision_resume_node_id VARCHAR(512) NOT NULL,
    selection_authority_revision VARCHAR(256) NOT NULL,
    selection_digest VARCHAR(64) NOT NULL,
    start_idempotency_key VARCHAR(512) NOT NULL,
    start_request_digest VARCHAR(64) NOT NULL,
    reservation_request_digest VARCHAR(64) NOT NULL,
    reservation_authorization_digest VARCHAR(64) NOT NULL,
    last_action_code VARCHAR(64) NOT NULL,
    last_operation_idempotency_key VARCHAR(512) NOT NULL,
    last_operation_request_digest VARCHAR(64) NOT NULL,
    last_evidence_digest VARCHAR(64) NOT NULL,
    cycle_number BIGINT NOT NULL,
    binding_revision BIGINT NOT NULL,
    binding_digest VARCHAR(64) NOT NULL,
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    CONSTRAINT pk_fw_wf_doc_binding PRIMARY KEY (id),
    CONSTRAINT uq_fw_wf_doc_binding_instance UNIQUE (tenant_id, instance_id),
    CONSTRAINT ck_fw_wf_doc_binding_numbers CHECK (cycle_number >= 0 AND binding_revision > 0)
);

CREATE INDEX ix_fw_wf_doc_binding_document
    ON fw_wf_doc_binding (tenant_id, document_type, document_id, updated_time);

CREATE TABLE fw_wf_doc_active_binding (
    id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    document_type VARCHAR(64) NOT NULL,
    document_id VARCHAR(512) NOT NULL,
    instance_id VARCHAR(512) NOT NULL,
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    CONSTRAINT pk_fw_wf_doc_active_binding PRIMARY KEY (id),
    CONSTRAINT uq_fw_wf_doc_active_document UNIQUE (tenant_id, document_type, document_id),
    CONSTRAINT uq_fw_wf_doc_active_instance UNIQUE (tenant_id, instance_id),
    CONSTRAINT fk_fw_wf_doc_active_binding FOREIGN KEY (tenant_id, instance_id)
        REFERENCES fw_wf_doc_binding (tenant_id, instance_id)
);
