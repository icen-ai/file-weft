ALTER TABLE fw_outbox_event
    ADD COLUMN trace_id varchar(128);

CREATE INDEX idx_fw_outbox_tenant_trace_time
    ON fw_outbox_event(tenant_id, trace_id, created_time DESC)
    WHERE trace_id IS NOT NULL;
