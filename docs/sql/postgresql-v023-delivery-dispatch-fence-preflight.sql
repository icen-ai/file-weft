-- FileWeft V023 delivery dispatch fence preflight (PostgreSQL).
--
-- Run this read-only script after stopping publication/review writes and old
-- delivery workers. Every issue_count must be zero before Flyway applies V023.
-- The migration intentionally fails rather than guessing an event identity.

WITH relevant AS (
    SELECT event.tenant_id,
           event.id,
           event.event_type,
           event.event_status,
           event.created_time,
           event.updated_time,
           event.payload_json ->> 'deliveryId' AS delivery_id
    FROM fw_outbox_event event
    WHERE event.event_type IN (
        'document.delivery.target.requested',
        'document.delivery.target.removal.requested'
    )
      AND jsonb_typeof(event.payload_json -> 'deliveryId') = 'string'
      AND btrim(event.payload_json ->> 'deliveryId') <> ''
), active_duplicates AS (
    SELECT tenant_id, delivery_id
    FROM relevant
    WHERE event_status IN ('PENDING', 'RETRY', 'RUNNING')
    GROUP BY tenant_id, delivery_id
    HAVING count(*) > 1
), missing_events AS (
    SELECT target.tenant_id, target.id
    FROM fw_document_delivery_target target
    WHERE NOT EXISTS (
        SELECT 1
        FROM relevant event
        WHERE event.tenant_id = target.tenant_id
          AND event.delivery_id = target.id
    )
), invalid_target_states AS (
    SELECT tenant_id, id
    FROM fw_document_delivery_target
    WHERE removal_status <> 'NOT_REQUESTED'
      AND delivery_status <> 'SUCCEEDED'
), ranked AS (
    SELECT relevant.*,
           row_number() OVER (
               PARTITION BY tenant_id, delivery_id
               ORDER BY
                   CASE WHEN event_status IN ('PENDING', 'RETRY') THEN 0 ELSE 1 END,
                   created_time DESC,
                   updated_time DESC,
                   id DESC
           ) AS position
    FROM relevant
), newest AS (
    SELECT *
    FROM ranked
    WHERE position = 1
), state_mismatches AS (
    SELECT target.tenant_id, target.id
    FROM fw_document_delivery_target target
    JOIN newest event
      ON event.tenant_id = target.tenant_id
     AND event.delivery_id = target.id
    WHERE NOT (
        (
            event.event_type = 'document.delivery.target.requested'
            AND target.removal_status = 'NOT_REQUESTED'
            AND (
                (target.delivery_status = 'PENDING' AND event.event_status = 'PENDING')
                OR (target.delivery_status = 'RETRYING' AND event.event_status = 'RETRY')
                OR (target.delivery_status = 'SUCCEEDED' AND event.event_status = 'SUCCESS')
                OR (target.delivery_status = 'FAILED' AND event.event_status = 'FAILED')
            )
        )
        OR
        (
            event.event_type = 'document.delivery.target.removal.requested'
            AND target.delivery_status = 'SUCCEEDED'
            AND (
                (target.removal_status = 'PENDING' AND event.event_status = 'PENDING')
                OR (target.removal_status = 'RETRYING' AND event.event_status = 'RETRY')
                OR (target.removal_status = 'SUCCEEDED' AND event.event_status = 'SUCCESS')
                OR (target.removal_status = 'FAILED' AND event.event_status = 'FAILED')
            )
        )
    )
)
SELECT 'running_delivery_events' AS check_name,
       count(*)::bigint AS issue_count
FROM relevant
WHERE event_status = 'RUNNING'
UNION ALL
SELECT 'targets_with_multiple_active_events', count(*)::bigint
FROM active_duplicates
UNION ALL
SELECT 'targets_without_delivery_events', count(*)::bigint
FROM missing_events
UNION ALL
SELECT 'invalid_delivery_removal_states', count(*)::bigint
FROM invalid_target_states
UNION ALL
SELECT 'newest_event_state_mismatches', count(*)::bigint
FROM state_mismatches
ORDER BY check_name;

-- Optional evidence for manual repair. These queries return identifiers only;
-- they do not mutate or silently close work.
WITH relevant AS (
    SELECT tenant_id,
           event_status,
           payload_json ->> 'deliveryId' AS delivery_id
    FROM fw_outbox_event
    WHERE event_type IN (
        'document.delivery.target.requested',
        'document.delivery.target.removal.requested'
    )
      AND jsonb_typeof(payload_json -> 'deliveryId') = 'string'
      AND btrim(payload_json ->> 'deliveryId') <> ''
)
SELECT tenant_id, delivery_id, count(*) AS active_event_count
FROM relevant
WHERE event_status IN ('PENDING', 'RETRY', 'RUNNING')
GROUP BY tenant_id, delivery_id
HAVING count(*) > 1
ORDER BY tenant_id, delivery_id;

SELECT target.tenant_id, target.id AS delivery_id
FROM fw_document_delivery_target target
WHERE NOT EXISTS (
    SELECT 1
    FROM fw_outbox_event event
    WHERE event.tenant_id = target.tenant_id
      AND event.event_type IN (
          'document.delivery.target.requested',
          'document.delivery.target.removal.requested'
      )
      AND jsonb_typeof(event.payload_json -> 'deliveryId') = 'string'
      AND event.payload_json ->> 'deliveryId' = target.id
)
ORDER BY target.tenant_id, target.id;
