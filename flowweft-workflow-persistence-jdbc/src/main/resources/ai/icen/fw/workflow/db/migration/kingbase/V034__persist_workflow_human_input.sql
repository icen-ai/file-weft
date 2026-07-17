-- Durable, tenant-scoped human-input evidence. Comment content is normalized into closed tokens;
-- there is deliberately no raw-HTML column.
CREATE TABLE fw_wf_human_input_idem (
    id varchar(64) NOT NULL, tenant_id varchar(512) NOT NULL,
    idempotency_key varchar(512) NOT NULL, operation_code varchar(64) NOT NULL,
    request_digest varchar(64) NOT NULL, reservation_status varchar(32) NOT NULL,
    lease_id varchar(512) NOT NULL, fencing_token bigint NOT NULL,
    lease_expires_time bigint NOT NULL, result_kind varchar(64),
    result_digest varchar(64), result_payload bytea,
    provider_receipt_digest varchar(64), receipt_expires_time bigint,
    record_version bigint NOT NULL, created_time bigint NOT NULL, updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id), UNIQUE (tenant_id, idempotency_key),
    CHECK (fencing_token > 0 AND record_version > 0 AND lease_expires_time >= 0 AND
        created_time >= 0 AND updated_time >= created_time),
    CHECK (
        (reservation_status = 'reserved' AND result_kind IS NULL AND result_digest IS NULL AND
            result_payload IS NULL AND provider_receipt_digest IS NULL AND receipt_expires_time IS NULL) OR
        (reservation_status = 'completed' AND result_kind IS NOT NULL AND result_digest IS NOT NULL AND
            result_payload IS NOT NULL AND (
            (result_kind = 'comment' AND provider_receipt_digest IS NULL AND receipt_expires_time IS NULL) OR
            (result_kind IN ('form', 'notification') AND provider_receipt_digest IS NOT NULL AND
                receipt_expires_time IS NOT NULL AND receipt_expires_time >= updated_time)
        ))
    )
);
CREATE INDEX idx_fw_wf_human_input_lease
    ON fw_wf_human_input_idem(tenant_id, reservation_status, lease_expires_time);

CREATE TABLE fw_wf_form_submission_ref (
    id varchar(512) NOT NULL, tenant_id varchar(512) NOT NULL, submission_version bigint NOT NULL,
    form_key varchar(128) NOT NULL, form_version varchar(128) NOT NULL,
    form_binding_digest varchar(64) NOT NULL, form_digest varchar(64) NOT NULL,
    schema_registry_id varchar(128) NOT NULL, schema_id varchar(128) NOT NULL,
    schema_version varchar(128) NOT NULL, schema_dialect varchar(128) NOT NULL,
    schema_digest varchar(64) NOT NULL, ui_schema_version varchar(128), ui_schema_digest varchar(64),
    submitted_by_type varchar(64) NOT NULL, submitted_by_id varchar(512) NOT NULL,
    canonical_payload_digest varchar(64) NOT NULL, payload_size_bytes integer NOT NULL,
    validation_receipt_digest varchar(64) NOT NULL, field_access_receipt_digest varchar(64) NOT NULL,
    submission_digest varchar(64) NOT NULL,
    provider_receipt_digest varchar(64) NOT NULL, receipt_expires_time bigint NOT NULL,
    occurred_time bigint NOT NULL, created_time bigint NOT NULL, updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id, submission_version),
    CHECK (submission_version >= 0 AND payload_size_bytes > 0 AND occurred_time >= 0 AND
        receipt_expires_time >= occurred_time AND updated_time >= created_time)
);

CREATE TABLE fw_wf_structured_comment (
    id varchar(512) NOT NULL, tenant_id varchar(512) NOT NULL, comment_version bigint NOT NULL,
    instance_id varchar(512) NOT NULL, instance_version bigint NOT NULL,
    work_item_id varchar(512), work_item_version bigint,
    author_type varchar(64) NOT NULL, author_id varchar(512) NOT NULL,
    token_schema_version integer NOT NULL, document_digest varchar(64) NOT NULL,
    snapshot_digest varchar(64) NOT NULL, authorization_receipt_digest varchar(64) NOT NULL,
    mention_attestation_digest varchar(64), occurred_time bigint NOT NULL,
    created_time bigint NOT NULL, updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id, comment_version),
    CHECK (comment_version >= 0 AND instance_version >= 0 AND token_schema_version > 0 AND
        occurred_time >= 0 AND updated_time >= created_time),
    CHECK ((work_item_id IS NULL AND work_item_version IS NULL) OR
        (work_item_id IS NOT NULL AND work_item_version IS NOT NULL AND work_item_version >= 0))
);
CREATE INDEX idx_fw_wf_structured_comment_instance
    ON fw_wf_structured_comment(tenant_id, instance_id, occurred_time, id);

CREATE TABLE fw_wf_structured_comment_token (
    id varchar(64) NOT NULL, tenant_id varchar(512) NOT NULL,
    comment_id varchar(512) NOT NULL, comment_version bigint NOT NULL, token_ordinal integer NOT NULL,
    token_kind varchar(32) NOT NULL, text_content text,
    principal_type varchar(64), principal_id varchar(512), display_name_snapshot varchar(1024),
    token_digest varchar(64) NOT NULL, created_time bigint NOT NULL, updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, comment_id, comment_version, token_ordinal),
    CHECK (token_ordinal >= 0 AND updated_time >= created_time),
    CHECK (
        (token_kind = 'text' AND text_content IS NOT NULL AND principal_type IS NULL AND
            principal_id IS NULL AND display_name_snapshot IS NULL) OR
        (token_kind = 'mention' AND text_content IS NULL AND principal_type IS NOT NULL AND
            principal_id IS NOT NULL AND display_name_snapshot IS NOT NULL)
    )
);

CREATE TABLE fw_wf_mention_notification_result (
    id varchar(64) NOT NULL, tenant_id varchar(512) NOT NULL,
    idempotency_key varchar(512) NOT NULL, delivery_status varchar(32) NOT NULL,
    provider_message_ref varchar(512), evidence_digest varchar(64) NOT NULL,
    delivery_digest varchar(64) NOT NULL, provider_receipt_digest varchar(64) NOT NULL,
    receipt_expires_time bigint NOT NULL, created_time bigint NOT NULL, updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id), UNIQUE (tenant_id, idempotency_key),
    CHECK (
        (delivery_status = 'accepted' AND provider_message_ref IS NOT NULL) OR
        (delivery_status = 'suppressed' AND provider_message_ref IS NULL)
    ),
    CHECK (receipt_expires_time >= created_time AND updated_time >= created_time)
);
