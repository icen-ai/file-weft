ALTER TABLE fw_task
    ADD COLUMN lease_token varchar(128);

CREATE INDEX idx_fw_task_running_lease_expiry
    ON fw_task(lease_expire_time, created_time, id);

CREATE INDEX idx_fw_task_legacy_running_updated
    ON fw_task(updated_time, created_time, id);
