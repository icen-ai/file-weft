-- FileWeft V026 preflight. Run with psql ON_ERROR_STOP before stopping the
-- old approval nodes. This script is read-only and fails when existing host
-- identities would violate the fixed 256 UTF-16-unit safety contract.

DO $$
DECLARE
    invalid_identity_count bigint;
BEGIN
    SELECT COUNT(*)
      INTO invalid_identity_count
      FROM (
            SELECT operator_id AS identity_value
              FROM fw_audit_record
             WHERE operator_id IS NOT NULL
            UNION ALL
            SELECT operator_id
              FROM fw_operation_log
             WHERE operator_id IS NOT NULL
            UNION ALL
            SELECT confirmed_by
              FROM fw_agent_suggestion_confirmation
            UNION ALL
            SELECT assignee_id
              FROM fw_workflow_task
             WHERE assignee_id IS NOT NULL
      ) identity
     WHERE NOT (
        char_length(identity_value) BETWEEN 1 AND 256
        AND char_length(identity_value)
            + char_length(regexp_replace(identity_value, U&'[^\+010000-\+10FFFF]', '', 'g')) <= 256
        AND identity_value = btrim(identity_value)
        AND identity_value !~ U&'[\0001-\001F\007F-\009F]'
        AND identity_value !~ U&'^[\0020\00A0\1680\2000-\200A\2028-\2029\202F\205F\3000]|[\0020\00A0\1680\2000-\200A\2028-\2029\202F\205F\3000]$'
        AND identity_value !~ U&'[\00AD\0600-\0605\061C\06DD\070F\0890-\0891\08E2\180E\200B-\200F\202A-\202E\2060-\2064\2066-\206F\FEFF\FFF9-\FFFB\+0110BD\+0110CD\+013430-\+01343F\+01BCA0-\+01BCA3\+01D173-\+01D17A\+0E0001\+0E0020-\+0E007F]'
     );

    IF invalid_identity_count <> 0 THEN
        RAISE EXCEPTION
            'V026 preflight found % unsafe host identity value(s); repair the owning user-realm mapping before migration.',
            invalid_identity_count;
    END IF;
END
$$;

SELECT task_state, COUNT(*) AS task_count
  FROM fw_workflow_task
 GROUP BY task_state
 ORDER BY task_state;

SELECT COUNT(*) AS pending_workflow_count
  FROM fw_workflow_instance
 WHERE state = 'PENDING';
