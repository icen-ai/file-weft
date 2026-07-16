-- Durable provider-call fence for safe mention notifications. It stores only digests and
-- operational evidence; comment text, rendered HTML, headers and provider secrets are forbidden.
CREATE TABLE fw_wf_mention_notification_checkpoint (
    id varchar(64) NOT NULL, tenant_id varchar(512) NOT NULL,
    idempotency_key varchar(512) NOT NULL, operation_request_digest varchar(64) NOT NULL,
    lease_id varchar(512) NOT NULL, fencing_token bigint NOT NULL,
    provider_request_digest varchar(64) NOT NULL, checkpoint_status varchar(32) NOT NULL,
    evidence_digest varchar(64), checkpoint_digest varchar(64) NOT NULL,
    record_version bigint NOT NULL, checkpointed_time bigint NOT NULL,
    created_time bigint NOT NULL, updated_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, idempotency_key),
    CHECK (fencing_token > 0 AND record_version > 0 AND checkpointed_time >= 0 AND
        created_time >= 0 AND updated_time >= checkpointed_time AND updated_time >= created_time),
    CHECK (
        (checkpoint_status = 'provider-call-started' AND evidence_digest IS NULL) OR
        (checkpoint_status IN ('outcome-unknown', 'accepted', 'not-sent', 'terminal-failure') AND
            evidence_digest IS NOT NULL)
    )
);
CREATE INDEX idx_fw_wf_mention_checkpoint_status
    ON fw_wf_mention_notification_checkpoint(
        tenant_id, checkpoint_status, updated_time, id
    );
