-- Run with autocommit enabled before V025 on a production database whose
-- fw_audit_record table is too large for a blocking CREATE INDEX window.
-- The preflight deliberately stops when a failed earlier CONCURRENTLY run (or
-- an operator-created index) left the expected name with the wrong definition.
DO $fileweft$
DECLARE
    named_relation_exists boolean;
    matching_index_count integer;
BEGIN
    SELECT EXISTS (
        SELECT 1
          FROM pg_class named_relation
          JOIN pg_namespace named_namespace
            ON named_namespace.oid = named_relation.relnamespace
         WHERE named_namespace.nspname = current_schema()
           AND named_relation.relname = 'idx_fw_audit_tenant_resource_time_id'
    ) INTO named_relation_exists;

    SELECT COUNT(*)
      INTO matching_index_count
      FROM pg_index index_row
      JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
      JOIN pg_namespace index_namespace ON index_namespace.oid = index_class.relnamespace
      JOIN pg_class table_class ON table_class.oid = index_row.indrelid
      JOIN pg_namespace table_namespace ON table_namespace.oid = table_class.relnamespace
      JOIN pg_am access_method ON access_method.oid = index_class.relam
     WHERE index_namespace.nspname = current_schema()
       AND index_class.relname = 'idx_fw_audit_tenant_resource_time_id'
       AND index_class.relkind = 'i'
       AND table_namespace.nspname = current_schema()
       AND table_class.relname = 'fw_audit_record'
       AND access_method.amname = 'btree'
       AND index_row.indisvalid
       AND index_row.indisready
       AND index_row.indislive
       AND NOT index_row.indisunique
       AND NOT index_row.indisexclusion
       AND index_row.indpred IS NULL
       AND index_row.indexprs IS NULL
       AND index_row.indnkeyatts = 5
       AND index_row.indnatts = 5
       AND (
            SELECT ARRAY_AGG(attribute.attname::text ORDER BY key_column.ordinality)
              FROM UNNEST(index_row.indkey::smallint[]) WITH ORDINALITY AS key_column(attnum, ordinality)
              JOIN pg_attribute attribute
                ON attribute.attrelid = index_row.indrelid
               AND attribute.attnum = key_column.attnum
       ) = ARRAY['tenant_id', 'resource_type', 'resource_id', 'created_time', 'id']::text[]
       AND (
            SELECT ARRAY_AGG(index_option ORDER BY option_column.ordinality)
              FROM UNNEST(index_row.indoption::smallint[]) WITH ORDINALITY AS option_column(index_option, ordinality)
       ) = ARRAY[0, 0, 0, 3, 3]::smallint[]
       AND pg_get_indexdef(index_row.indexrelid) = FORMAT(
            'CREATE INDEX %I ON %I.%I USING btree (tenant_id, resource_type, resource_id, created_time DESC, id DESC)',
            'idx_fw_audit_tenant_resource_time_id', current_schema(), 'fw_audit_record'
       );

    IF named_relation_exists AND matching_index_count <> 1 THEN
        RAISE EXCEPTION USING
            MESSAGE = 'The name idx_fw_audit_tenant_resource_time_id already exists with an invalid or incompatible definition.',
            HINT = 'Inspect it, then run DROP INDEX CONCURRENTLY <schema>.idx_fw_audit_tenant_resource_time_id before rerunning this script.';
    END IF;
END
$fileweft$;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fw_audit_tenant_resource_time_id
    ON fw_audit_record(tenant_id, resource_type, resource_id, created_time DESC, id DESC);

DO $fileweft$
DECLARE
    matching_index_count integer;
BEGIN
    SELECT COUNT(*)
      INTO matching_index_count
      FROM pg_index index_row
      JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
      JOIN pg_namespace index_namespace ON index_namespace.oid = index_class.relnamespace
      JOIN pg_class table_class ON table_class.oid = index_row.indrelid
      JOIN pg_namespace table_namespace ON table_namespace.oid = table_class.relnamespace
      JOIN pg_am access_method ON access_method.oid = index_class.relam
     WHERE index_namespace.nspname = current_schema()
       AND index_class.relname = 'idx_fw_audit_tenant_resource_time_id'
       AND index_class.relkind = 'i'
       AND table_namespace.nspname = current_schema()
       AND table_class.relname = 'fw_audit_record'
       AND access_method.amname = 'btree'
       AND index_row.indisvalid
       AND index_row.indisready
       AND index_row.indislive
       AND NOT index_row.indisunique
       AND NOT index_row.indisexclusion
       AND index_row.indpred IS NULL
       AND index_row.indexprs IS NULL
       AND index_row.indnkeyatts = 5
       AND index_row.indnatts = 5
       AND (
            SELECT ARRAY_AGG(attribute.attname::text ORDER BY key_column.ordinality)
              FROM UNNEST(index_row.indkey::smallint[]) WITH ORDINALITY AS key_column(attnum, ordinality)
              JOIN pg_attribute attribute
                ON attribute.attrelid = index_row.indrelid
               AND attribute.attnum = key_column.attnum
       ) = ARRAY['tenant_id', 'resource_type', 'resource_id', 'created_time', 'id']::text[]
       AND (
            SELECT ARRAY_AGG(index_option ORDER BY option_column.ordinality)
              FROM UNNEST(index_row.indoption::smallint[]) WITH ORDINALITY AS option_column(index_option, ordinality)
       ) = ARRAY[0, 0, 0, 3, 3]::smallint[]
       AND pg_get_indexdef(index_row.indexrelid) = FORMAT(
            'CREATE INDEX %I ON %I.%I USING btree (tenant_id, resource_type, resource_id, created_time DESC, id DESC)',
            'idx_fw_audit_tenant_resource_time_id', current_schema(), 'fw_audit_record'
       );

    IF matching_index_count <> 1 THEN
        RAISE EXCEPTION 'Concurrent audit-log index validation failed after creation.';
    END IF;
END
$fileweft$;

ANALYZE fw_audit_record;

-- Rollback after the new audit-log query has been removed from all nodes:
-- DROP INDEX CONCURRENTLY IF EXISTS idx_fw_audit_tenant_resource_time_id;
