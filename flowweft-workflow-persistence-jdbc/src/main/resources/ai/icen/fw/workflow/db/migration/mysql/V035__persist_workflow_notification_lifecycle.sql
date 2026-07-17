-- Durable, tenant-scoped notification outbox. Provider calls are always made after the
-- short enqueue/checkpoint transactions represented by these tables have committed.
CREATE TABLE fw_wf_notification_batch (
    id varbinary(64) NOT NULL, tenant_id varbinary(512) NOT NULL,
    origin_idempotency_key varbinary(512) NOT NULL, origin_intent_digest varchar(64) NOT NULL,
    batch_digest varchar(64) NOT NULL, authorization_evidence_digest varchar(64) NOT NULL,
    envelope_count integer NOT NULL, first_envelope_id varbinary(512) NOT NULL,
    enqueued_time bigint NOT NULL, created_time bigint NOT NULL, updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id), UNIQUE (tenant_id, origin_idempotency_key),
    CHECK (envelope_count > 0 AND envelope_count <= 256 AND enqueued_time >= 0 AND
        created_time = enqueued_time AND updated_time >= created_time)
);

CREATE TABLE fw_wf_notification_envelope (
    id varbinary(512) NOT NULL, tenant_id varbinary(512) NOT NULL,
    batch_id varbinary(64) NOT NULL, batch_ordinal integer NOT NULL,
    deduplication_key varbinary(512) NOT NULL, origin_intent_digest varchar(64) NOT NULL,
    envelope_digest varchar(64) NOT NULL, envelope_payload longblob NOT NULL,
    queue_status varchar(32) NOT NULL, record_version bigint NOT NULL, attempt_count integer NOT NULL,
    lease_id varbinary(512), worker_id varbinary(512), fencing_token bigint NOT NULL,
    lease_acquired_time bigint, lease_expires_time bigint, next_attempt_time bigint,
    provider_request_digest varchar(64), provider_evidence_payload longblob,
    provider_receipt_digest varchar(64), delivery_digest varchar(64),
    outcome_evidence_digest varchar(64), last_mutation_digest varchar(64) NOT NULL,
    authorization_evidence_digest varchar(64) NOT NULL,
    created_time bigint NOT NULL, updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, deduplication_key),
    UNIQUE (tenant_id, batch_id, batch_ordinal),
    CHECK (batch_ordinal >= 0 AND record_version >= 0 AND attempt_count >= 0 AND
        fencing_token >= 0 AND created_time >= 0 AND updated_time >= created_time),
    CHECK ((lease_id IS NULL AND worker_id IS NULL AND lease_acquired_time IS NULL AND lease_expires_time IS NULL) OR
        (lease_id IS NOT NULL AND worker_id IS NOT NULL AND lease_acquired_time IS NOT NULL AND
            lease_expires_time IS NOT NULL AND fencing_token > 0 AND lease_expires_time > lease_acquired_time)),
    CHECK ((provider_evidence_payload IS NULL AND provider_receipt_digest IS NULL AND delivery_digest IS NULL) OR
        (provider_evidence_payload IS NOT NULL AND provider_receipt_digest IS NOT NULL AND delivery_digest IS NOT NULL)),
    CHECK (
        (queue_status = 'queued' AND record_version = 0 AND attempt_count = 0 AND lease_id IS NULL AND
            next_attempt_time IS NULL AND provider_request_digest IS NULL AND provider_evidence_payload IS NULL AND
            outcome_evidence_digest IS NULL) OR
        (queue_status = 'leased' AND attempt_count > 0 AND lease_id IS NOT NULL AND next_attempt_time IS NULL AND
            provider_request_digest IS NULL AND provider_evidence_payload IS NULL) OR
        (queue_status = 'provider-call-started' AND attempt_count > 0 AND lease_id IS NOT NULL AND
            next_attempt_time IS NULL AND provider_request_digest IS NOT NULL AND provider_evidence_payload IS NULL) OR
        (queue_status = 'retry-wait' AND attempt_count > 0 AND lease_id IS NULL AND
            next_attempt_time IS NOT NULL AND next_attempt_time > updated_time AND
            provider_evidence_payload IS NULL AND outcome_evidence_digest IS NOT NULL) OR
        (queue_status = 'accepted' AND attempt_count > 0 AND lease_id IS NULL AND next_attempt_time IS NULL AND
            provider_evidence_payload IS NOT NULL AND outcome_evidence_digest IS NOT NULL) OR
        (queue_status = 'suppressed' AND lease_id IS NULL AND next_attempt_time IS NULL AND
            outcome_evidence_digest IS NOT NULL) OR
        (queue_status IN ('outcome-unknown', 'terminal-failure') AND attempt_count > 0 AND lease_id IS NULL AND
            next_attempt_time IS NULL AND provider_evidence_payload IS NULL AND outcome_evidence_digest IS NOT NULL) OR
        (queue_status IN ('delivered', 'transient-bounce', 'permanent-bounce') AND attempt_count > 0 AND
            lease_id IS NULL AND next_attempt_time IS NULL AND provider_evidence_payload IS NOT NULL AND
            outcome_evidence_digest IS NOT NULL)
    )
);
CREATE INDEX idx_fw_wf_notification_envelope_ready
    ON fw_wf_notification_envelope(tenant_id, queue_status, next_attempt_time, updated_time, id);
CREATE INDEX idx_fw_wf_notification_lease
    ON fw_wf_notification_envelope(tenant_id, queue_status, lease_expires_time, id);

CREATE TABLE fw_wf_notification_delivery_report (
    id varbinary(512) NOT NULL, tenant_id varbinary(512) NOT NULL, envelope_id varbinary(512) NOT NULL,
    provider_id varchar(128) NOT NULL, provider_revision varchar(1024) NOT NULL,
    provider_message_ref varbinary(1024) NOT NULL, delivery_status varchar(32) NOT NULL,
    evidence_digest varchar(64) NOT NULL, report_digest varchar(64) NOT NULL,
    expected_version bigint NOT NULL, authorization_evidence_digest varchar(64) NOT NULL,
    mutation_digest varchar(64) NOT NULL, observed_time bigint NOT NULL,
    created_time bigint NOT NULL, updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    CHECK (delivery_status IN ('delivered', 'transient-bounce', 'permanent-bounce') AND
        expected_version >= 0 AND observed_time >= 0 AND created_time = observed_time AND
        updated_time >= created_time)
);
CREATE INDEX idx_fw_wf_notification_report_envelope
    ON fw_wf_notification_delivery_report(tenant_id, envelope_id, observed_time, id);
