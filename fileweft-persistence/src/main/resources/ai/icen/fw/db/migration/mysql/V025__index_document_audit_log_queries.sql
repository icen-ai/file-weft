CREATE INDEX idx_fw_audit_tenant_resource_time_id
    ON fw_audit_record(tenant_id, resource_type, resource_id, created_time DESC, id DESC);

-- The PostgreSQL original introspects pg_index/pg_class to verify the exact
-- index definition. MySQL does not expose equivalent metadata in a portable
-- way, so the deterministic keyset index above is the migration-level guarantee.
-- Application-layer tests cover the audit-log query ordering contract.
