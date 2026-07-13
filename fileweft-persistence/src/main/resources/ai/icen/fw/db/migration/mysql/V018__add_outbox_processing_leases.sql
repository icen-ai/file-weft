ALTER TABLE fw_outbox_event
    ADD COLUMN lease_owner varchar(256);

ALTER TABLE fw_outbox_event
    ADD COLUMN lease_token varchar(128);

ALTER TABLE fw_outbox_event
    ADD COLUMN lease_expire_time bigint NOT NULL DEFAULT 0;

CREATE INDEX idx_fw_outbox_running_lease_expiry
    ON fw_outbox_event(lease_expire_time, created_time, id);

CREATE INDEX idx_fw_outbox_legacy_running_updated
    ON fw_outbox_event(updated_time, created_time, id);
