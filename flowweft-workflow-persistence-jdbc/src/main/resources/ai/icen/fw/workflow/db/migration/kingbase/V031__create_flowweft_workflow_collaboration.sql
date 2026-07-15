CREATE TABLE fw_wf_form_definition (
    id varchar(512) NOT NULL, tenant_id varchar(512) NOT NULL,
    form_key varchar(256) NOT NULL, title varchar(1024) NOT NULL,
    lifecycle_status varchar(64) NOT NULL, latest_version_id varchar(512),
    created_time bigint NOT NULL, updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id), UNIQUE (tenant_id, form_key)
);

CREATE TABLE fw_wf_form_version (
    id varchar(512) NOT NULL, tenant_id varchar(512) NOT NULL,
    form_definition_id varchar(512) NOT NULL, form_version varchar(128) NOT NULL,
    schema_digest varchar(64) NOT NULL, ui_schema_digest varchar(64) NOT NULL,
    field_policy_digest varchar(64) NOT NULL, schema_payload bytea NOT NULL,
    ui_schema_payload bytea, field_policy_payload bytea NOT NULL,
    created_time bigint NOT NULL, updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, form_definition_id, form_version)
);

CREATE TABLE fw_wf_form_submission (
    id varchar(512) NOT NULL, tenant_id varchar(512) NOT NULL,
    instance_id varchar(512) NOT NULL, work_item_id varchar(512),
    form_version_id varchar(512) NOT NULL, submission_digest varchar(64) NOT NULL,
    payload bytea NOT NULL, submitted_by_type varchar(64) NOT NULL,
    submitted_by_id varchar(512) NOT NULL, authorization_receipt_digest varchar(64) NOT NULL,
    occurred_time bigint NOT NULL, created_time bigint NOT NULL, updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX idx_fw_wf_form_submission_instance ON fw_wf_form_submission(tenant_id, instance_id, occurred_time);

CREATE TABLE fw_wf_comment (
    id varchar(512) NOT NULL, tenant_id varchar(512) NOT NULL,
    instance_id varchar(512) NOT NULL, work_item_id varchar(512), parent_comment_id varchar(512),
    author_type varchar(64) NOT NULL, author_id varchar(512) NOT NULL,
    token_format_version integer NOT NULL, content_digest varchar(64) NOT NULL,
    token_payload bytea NOT NULL, visibility_digest varchar(64) NOT NULL,
    occurred_time bigint NOT NULL, created_time bigint NOT NULL, updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id), CHECK (token_format_version > 0)
);
CREATE INDEX idx_fw_wf_comment_thread ON fw_wf_comment(tenant_id, instance_id, occurred_time, id);

CREATE TABLE fw_wf_comment_mention (
    id varchar(512) NOT NULL, tenant_id varchar(512) NOT NULL,
    instance_id varchar(512) NOT NULL, comment_id varchar(512) NOT NULL,
    token_ordinal integer NOT NULL, principal_type varchar(64) NOT NULL,
    principal_id varchar(512) NOT NULL, display_name_snapshot varchar(1024) NOT NULL,
    visibility_receipt_digest varchar(64) NOT NULL,
    created_time bigint NOT NULL, updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id), UNIQUE (tenant_id, comment_id, token_ordinal)
);

CREATE TABLE fw_wf_notification (
    id varchar(512) NOT NULL, tenant_id varchar(512) NOT NULL,
    instance_id varchar(512) NOT NULL, intent_type varchar(128) NOT NULL,
    recipient_type varchar(64) NOT NULL, recipient_id varchar(512) NOT NULL,
    content_digest varchar(64) NOT NULL, visibility_scope_digest varchar(64) NOT NULL,
    notification_status varchar(64) NOT NULL, attempt_count integer NOT NULL,
    next_attempt_time bigint, provider_receipt_digest varchar(64), failure_digest varchar(64),
    created_time bigint NOT NULL, updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id), CHECK (attempt_count >= 0)
);
CREATE INDEX idx_fw_wf_notification_ready ON fw_wf_notification(tenant_id, notification_status, next_attempt_time);

CREATE TABLE fw_wf_migration_plan (
    id varchar(512) NOT NULL, tenant_id varchar(512) NOT NULL,
    source_definition_id varchar(512) NOT NULL, source_definition_digest varchar(64) NOT NULL,
    target_definition_id varchar(512) NOT NULL, target_definition_digest varchar(64) NOT NULL,
    node_mapping_digest varchar(64) NOT NULL, variable_transform_digest varchar(64) NOT NULL,
    timer_policy_digest varchar(64) NOT NULL, subscription_policy_digest varchar(64) NOT NULL,
    plan_status varchar(64) NOT NULL, created_by_type varchar(64) NOT NULL,
    created_by_id varchar(512) NOT NULL, created_time bigint NOT NULL, updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE fw_wf_migration_run (
    id varchar(512) NOT NULL, tenant_id varchar(512) NOT NULL,
    migration_plan_id varchar(512) NOT NULL, run_status varchar(64) NOT NULL,
    dry_run boolean NOT NULL, authorization_receipt_digest varchar(64) NOT NULL,
    requested_by_type varchar(64) NOT NULL, requested_by_id varchar(512) NOT NULL,
    started_time bigint, completed_time bigint,
    created_time bigint NOT NULL, updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX idx_fw_wf_migration_run_status ON fw_wf_migration_run(tenant_id, run_status, created_time);

CREATE TABLE fw_wf_migration_item (
    id varchar(512) NOT NULL, tenant_id varchar(512) NOT NULL,
    migration_run_id varchar(512) NOT NULL, instance_id varchar(512) NOT NULL,
    source_instance_version bigint NOT NULL, target_instance_version bigint,
    item_status varchar(64) NOT NULL, evidence_digest varchar(64) NOT NULL,
    failure_digest varchar(64), created_time bigint NOT NULL, updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id), UNIQUE (tenant_id, migration_run_id, instance_id)
);
