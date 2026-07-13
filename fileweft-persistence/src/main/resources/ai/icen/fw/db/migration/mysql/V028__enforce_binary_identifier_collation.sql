-- MySQL's common utf8mb4 defaults compare text case- and accent-insensitively,
-- which is unsafe for tenant boundaries and opaque identifiers. Convert every
-- FileWeft-owned business table so tenant/user/business/idempotency keys, opaque
-- IDs, V017 generated uniqueness keys, and their indexes all use one binary,
-- NO PAD comparison contract. utf8mb4_0900_bin is intentional: the legacy
-- utf8mb4_bin collation pads trailing spaces and can still collapse distinct
-- Java String identifiers such as "TenantA" and "TenantA ".
-- utf8mb4_0900_bin is available from the FileWeft MySQL baseline, 8.0.17;
-- MySqlDatabaseSupport rejects earlier, unverifiable, non-native, or 9.x hosts.
--
-- This intentionally gives display text (for example titles and comments) the
-- same case- and accent-sensitive comparison/sort behavior. The whole-table
-- policy is a security-first trade-off: it prevents a future textual key from
-- silently inheriting a case-insensitive table default and keeps semantics
-- auditable instead of relying on an incomplete per-column allowlist.
-- CONVERT may rebuild and lock populated tables; apply this migration in a
-- planned maintenance window with FileWeft writers and workers stopped.

ALTER TABLE fw_file_object
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

ALTER TABLE fw_asset
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

ALTER TABLE fw_document
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

ALTER TABLE fw_document_version
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

ALTER TABLE fw_outbox_event
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

ALTER TABLE fw_sync_record
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

ALTER TABLE fw_audit_record
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

ALTER TABLE fw_workflow_instance
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

ALTER TABLE fw_workflow_task
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

ALTER TABLE fw_document_delivery_target
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

ALTER TABLE fw_task
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

ALTER TABLE fw_doctor_record
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

ALTER TABLE fw_operation_log
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

ALTER TABLE fw_agent_result
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

ALTER TABLE fw_agent_suggestion_confirmation
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

ALTER TABLE fw_upload_session
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

ALTER TABLE fw_upload_session_part
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

ALTER TABLE fw_idempotency_record
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
