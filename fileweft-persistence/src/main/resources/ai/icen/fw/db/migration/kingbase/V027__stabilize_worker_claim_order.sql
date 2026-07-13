-- Keep migration versions aligned across supported products and make the
-- stable claim ordering directly indexable for large worker queues.
CREATE INDEX IF NOT EXISTS idx_fw_outbox_claim_order
    ON fw_outbox_event(created_time, id);

CREATE INDEX IF NOT EXISTS idx_fw_task_claim_order
    ON fw_task(created_time, id);
