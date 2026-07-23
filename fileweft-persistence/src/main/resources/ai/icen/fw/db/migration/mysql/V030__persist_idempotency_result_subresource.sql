-- Same semantics as the PostgreSQL V030: the persisted first pending task of
-- a submit receipt is a stable framework-generated identifier, never user
-- input. V030 only appends a nullable column, so 0.3.1 nodes keep rolling
-- against the migrated schema without a write-stop window.
--
-- As with the other result columns, the status/shape contract is enforced at
-- FileWeft's trusted application boundary for MySQL.
ALTER TABLE fw_idempotency_record
    ADD COLUMN result_subresource_id varchar(256);
