-- MySQL can otherwise choose a filesort for the OR eligibility predicate and
-- lock every matching row before SKIP LOCKED is applied. These indexes pair
-- with the MySQL-only FORCE INDEX claim hint in the JDBC repositories.
CREATE INDEX idx_fw_outbox_claim_order
    ON fw_outbox_event(created_time, id);

CREATE INDEX idx_fw_task_claim_order
    ON fw_task(created_time, id);
