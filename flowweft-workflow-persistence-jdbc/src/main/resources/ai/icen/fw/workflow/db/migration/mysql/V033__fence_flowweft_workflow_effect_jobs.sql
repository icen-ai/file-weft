-- Adds bounded worker leasing and immutable participant-organization result evidence.
ALTER TABLE fw_wf_job ADD COLUMN record_version bigint NOT NULL DEFAULT 0;
ALTER TABLE fw_wf_job ADD COLUMN lease_id varbinary(512);
ALTER TABLE fw_wf_job ADD COLUMN worker_id varbinary(512);
ALTER TABLE fw_wf_job ADD COLUMN fencing_token bigint NOT NULL DEFAULT 0;
ALTER TABLE fw_wf_job ADD COLUMN lease_acquired_time bigint;
ALTER TABLE fw_wf_job ADD COLUMN lease_expires_time bigint;
ALTER TABLE fw_wf_job ADD COLUMN execution_mode varchar(64);
ALTER TABLE fw_wf_job ADD COLUMN claim_request_digest varbinary(64);
ALTER TABLE fw_wf_job ADD CONSTRAINT ck_fw_wf_job_version_fence
    CHECK (record_version >= 0 AND fencing_token >= 0);
ALTER TABLE fw_wf_job ADD CONSTRAINT ck_fw_wf_job_lease_complete CHECK (
    (lease_id IS NULL AND worker_id IS NULL AND lease_acquired_time IS NULL AND
        lease_expires_time IS NULL AND execution_mode IS NULL AND claim_request_digest IS NULL) OR
    (lease_id IS NOT NULL AND worker_id IS NOT NULL AND lease_acquired_time IS NOT NULL AND
        lease_expires_time IS NOT NULL AND execution_mode IS NOT NULL AND claim_request_digest IS NOT NULL AND
        lease_expires_time > lease_acquired_time)
);
CREATE INDEX idx_fw_wf_job_claim
    ON fw_wf_job(tenant_id, job_type, available_time, lease_expires_time, created_time);

ALTER TABLE fw_wf_human_candidate ADD COLUMN organization_authority varbinary(128);
ALTER TABLE fw_wf_human_candidate ADD COLUMN organization_snapshot_revision varbinary(256);
ALTER TABLE fw_wf_human_candidate ADD COLUMN resolution_request_digest varbinary(64);
ALTER TABLE fw_wf_human_candidate ADD COLUMN organization_provider_revision varbinary(256);
ALTER TABLE fw_wf_human_candidate ADD COLUMN organization_snapshot_digest varbinary(64);
ALTER TABLE fw_wf_human_candidate ADD COLUMN organization_snapshot_receipt_digest varbinary(64);
ALTER TABLE fw_wf_human_candidate ADD COLUMN organization_confirmation_revision varbinary(256);
ALTER TABLE fw_wf_human_candidate ADD COLUMN organization_confirmation_snapshot_digest varbinary(64);
ALTER TABLE fw_wf_human_candidate ADD COLUMN organization_confirmation_request_digest varbinary(64);
ALTER TABLE fw_wf_human_candidate ADD COLUMN organization_confirmation_receipt_digest varbinary(64);
ALTER TABLE fw_wf_human_candidate ADD CONSTRAINT ck_fw_wf_candidate_org_evidence CHECK (
    (organization_authority IS NULL AND organization_snapshot_revision IS NULL AND
        resolution_request_digest IS NULL AND organization_provider_revision IS NULL AND
        organization_snapshot_digest IS NULL AND organization_snapshot_receipt_digest IS NULL AND
        organization_confirmation_revision IS NULL AND organization_confirmation_snapshot_digest IS NULL AND
        organization_confirmation_request_digest IS NULL AND organization_confirmation_receipt_digest IS NULL) OR
    (organization_authority IS NOT NULL AND organization_snapshot_revision IS NOT NULL AND
        resolution_request_digest IS NOT NULL AND (
            (organization_provider_revision IS NULL AND organization_snapshot_digest IS NULL AND
                organization_snapshot_receipt_digest IS NULL AND organization_confirmation_revision IS NULL AND
                organization_confirmation_snapshot_digest IS NULL AND
                organization_confirmation_request_digest IS NULL AND
                organization_confirmation_receipt_digest IS NULL) OR
            (organization_provider_revision IS NOT NULL AND organization_snapshot_digest IS NOT NULL AND
                organization_snapshot_receipt_digest IS NOT NULL AND
                organization_confirmation_revision IS NOT NULL AND
                organization_confirmation_snapshot_digest IS NOT NULL AND
                organization_confirmation_request_digest IS NOT NULL AND
                organization_confirmation_receipt_digest IS NOT NULL)
        ))
);

CREATE TABLE fw_wf_effect_result (
    id varbinary(512) NOT NULL,
    tenant_id varbinary(512) NOT NULL,
    instance_id varbinary(512) NOT NULL,
    effect_id varbinary(512) NOT NULL,
    result_type varchar(128) NOT NULL,
    outcome_code varchar(64) NOT NULL,
    result_digest varbinary(64) NOT NULL,
    result_payload longblob NOT NULL,
    retry_time bigint,
    completed_time bigint NOT NULL,
    result_version bigint NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, effect_id),
    CHECK (result_version > 0),
    CHECK ((outcome_code = 'retryable-failure' AND retry_time IS NOT NULL AND retry_time > completed_time) OR
        (outcome_code <> 'retryable-failure' AND retry_time IS NULL))
);
CREATE INDEX idx_fw_wf_effect_result_instance
    ON fw_wf_effect_result(tenant_id, instance_id, created_time);
