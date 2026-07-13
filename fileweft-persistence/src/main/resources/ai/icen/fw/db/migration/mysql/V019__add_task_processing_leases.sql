ALTER TABLE fw_task
    ADD COLUMN lease_token varchar(128);

ALTER TABLE fw_task
    ADD COLUMN lease_expire_time bigint NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_fw_task_running_lease_expiry
    ON fw_task(lease_expire_time, created_time, id);

CREATE INDEX IF NOT EXISTS idx_fw_task_legacy_running_updated
    ON fw_task(updated_time, created_time, id);
