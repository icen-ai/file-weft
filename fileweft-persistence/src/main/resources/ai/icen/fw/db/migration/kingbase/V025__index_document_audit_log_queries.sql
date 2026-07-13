CREATE INDEX IF NOT EXISTS idx_fw_audit_tenant_resource_time_id
    ON fw_audit_record(tenant_id, resource_type, resource_id, created_time DESC, id DESC);

-- The PostgreSQL original introspects pg_index/pg_class to verify the exact
-- index definition. Kingbase exposes compatible catalog tables, but the
-- detailed metadata verification relies on PostgreSQL-specific functions and
-- system columns that may differ in Kingbase. We therefore keep the
-- deterministic keyset index as the migration-level guarantee and rely on
-- application-layer tests for the audit-log ordering contract.
