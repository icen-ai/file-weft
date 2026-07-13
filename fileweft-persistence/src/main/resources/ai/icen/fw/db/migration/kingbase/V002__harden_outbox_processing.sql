ALTER TABLE fw_outbox_event
    ADD COLUMN next_attempt_time bigint NOT NULL DEFAULT 0;

ALTER TABLE fw_outbox_event
    ADD COLUMN last_error varchar(1024);

CREATE INDEX idx_fw_outbox_available
    ON fw_outbox_event(event_status, next_attempt_time, created_time);
