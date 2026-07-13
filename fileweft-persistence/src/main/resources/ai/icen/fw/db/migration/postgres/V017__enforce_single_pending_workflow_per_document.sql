-- A document can have only one active local review workflow. Do not let the
-- unique index hide historical data corruption: report duplicate groups first
-- so operators can resolve or close the obsolete workflows deliberately.
DO $$
DECLARE
    duplicate_group_count bigint;
    duplicate_samples     text;
BEGIN
    SELECT COUNT(*)
    INTO duplicate_group_count
    FROM (
        SELECT tenant_id, document_id
        FROM fw_workflow_instance
        WHERE state = 'PENDING'
        GROUP BY tenant_id, document_id
        HAVING COUNT(*) > 1
    ) AS duplicate_groups;

    IF duplicate_group_count > 0 THEN
        SELECT string_agg(
            format(
                'tenant_id=%s, document_id=%s, pending_count=%s',
                tenant_id,
                document_id,
                pending_count
            ),
            '; ' ORDER BY tenant_id, document_id
        )
        INTO duplicate_samples
        FROM (
            SELECT tenant_id, document_id, COUNT(*) AS pending_count
            FROM fw_workflow_instance
            WHERE state = 'PENDING'
            GROUP BY tenant_id, document_id
            HAVING COUNT(*) > 1
            ORDER BY tenant_id, document_id
            LIMIT 10
        ) AS duplicate_group_samples;

        RAISE EXCEPTION USING
            ERRCODE = '23505',
            MESSAGE = format(
                'Cannot enforce one PENDING workflow per tenant/document: found %s duplicate group(s).',
                duplicate_group_count
            ),
            DETAIL = format('Examples (maximum 10): %s', duplicate_samples),
            HINT = 'Resolve or close duplicate PENDING workflows before retrying migration V017.';
    END IF;
END $$;

CREATE UNIQUE INDEX uq_fw_workflow_instance_tenant_document_pending
    ON fw_workflow_instance(tenant_id, document_id)
    WHERE state = 'PENDING';
