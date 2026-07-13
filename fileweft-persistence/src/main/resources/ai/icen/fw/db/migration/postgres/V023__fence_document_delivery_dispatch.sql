-- A pre-GA maintenance-window migration. Old workers do not understand the
-- target-level event fence, so related RUNNING work must be drained before the
-- new columns are backfilled and made mandatory.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM fw_outbox_event
        WHERE event_type IN (
            'document.delivery.target.requested',
            'document.delivery.target.removal.requested'
        )
          AND event_status = 'RUNNING'
    ) THEN
        RAISE EXCEPTION 'Delivery dispatch fencing requires all related RUNNING outbox events to be drained.'
            USING HINT = 'Stop old delivery workers, recover or finish RUNNING events, and retry the migration.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM (
            SELECT tenant_id, payload_json ->> 'deliveryId' AS delivery_id
            FROM fw_outbox_event
            WHERE event_type IN (
                'document.delivery.target.requested',
                'document.delivery.target.removal.requested'
            )
              AND jsonb_typeof(payload_json -> 'deliveryId') = 'string'
              AND btrim(payload_json ->> 'deliveryId') <> ''
              AND event_status IN ('PENDING', 'RETRY')
            GROUP BY tenant_id, payload_json ->> 'deliveryId'
            HAVING count(*) > 1
        ) active_dispatches
    ) THEN
        RAISE EXCEPTION 'A delivery target has more than one active outbox dispatch.'
            USING HINT = 'Resolve duplicate PENDING or RETRY events before retrying the migration.';
    END IF;

    IF EXISTS (
        SELECT 1
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
    ) THEN
        RAISE EXCEPTION 'A delivery target has no recoverable delivery or removal event.'
            USING HINT = 'Repair the target/outbox history explicitly; the migration will not invent an event identity.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM fw_document_delivery_target
        WHERE removal_status <> 'NOT_REQUESTED'
          AND delivery_status <> 'SUCCEEDED'
    ) THEN
        RAISE EXCEPTION 'A delivery target has an invalid delivery/removal state combination.'
            USING HINT = 'Any requested removal must belong to a successfully delivered target.';
    END IF;
END $$;

ALTER TABLE fw_document_delivery_target
    ADD COLUMN current_event_id varchar(64),
    ADD COLUMN current_operation varchar(16),
    ADD COLUMN dispatch_sequence bigint;

WITH relevant AS (
    SELECT event.tenant_id,
           event.id AS event_id,
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
), ranked AS (
    SELECT relevant.*,
           count(*) OVER (
               PARTITION BY tenant_id, delivery_id
           ) AS historical_sequence,
           row_number() OVER (
               PARTITION BY tenant_id, delivery_id
               ORDER BY
                   CASE WHEN event_status IN ('PENDING', 'RETRY') THEN 0 ELSE 1 END,
                   created_time DESC,
                   updated_time DESC,
                   event_id DESC
           ) AS position
    FROM relevant
)
UPDATE fw_document_delivery_target target
SET current_event_id = ranked.event_id,
    current_operation = CASE ranked.event_type
        WHEN 'document.delivery.target.requested' THEN 'DELIVERY'
        WHEN 'document.delivery.target.removal.requested' THEN 'REMOVAL'
    END,
    dispatch_sequence = ranked.historical_sequence
FROM ranked
WHERE ranked.position = 1
  AND ranked.tenant_id = target.tenant_id
  AND ranked.delivery_id = target.id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM fw_document_delivery_target
        WHERE current_event_id IS NULL
           OR current_operation IS NULL
           OR dispatch_sequence IS NULL
    ) THEN
        RAISE EXCEPTION 'Delivery dispatch backfill did not produce a complete event fence.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM fw_document_delivery_target target
        JOIN fw_outbox_event event
          ON event.tenant_id = target.tenant_id
         AND event.id = target.current_event_id
        WHERE NOT (
            (
                target.current_operation = 'DELIVERY'
                AND event.event_type = 'document.delivery.target.requested'
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
                target.current_operation = 'REMOVAL'
                AND event.event_type = 'document.delivery.target.removal.requested'
                AND target.delivery_status = 'SUCCEEDED'
                AND (
                    (target.removal_status = 'PENDING' AND event.event_status = 'PENDING')
                    OR (target.removal_status = 'RETRYING' AND event.event_status = 'RETRY')
                    OR (target.removal_status = 'SUCCEEDED' AND event.event_status = 'SUCCESS')
                    OR (target.removal_status = 'FAILED' AND event.event_status = 'FAILED')
                )
            )
        )
    ) THEN
        RAISE EXCEPTION 'Delivery target state does not match its newest recoverable outbox event.'
            USING HINT = 'Reconcile target and outbox terminal state before retrying the migration.';
    END IF;
END $$;

ALTER TABLE fw_document_delivery_target
    ALTER COLUMN current_event_id SET NOT NULL,
    ALTER COLUMN current_operation SET NOT NULL,
    ALTER COLUMN dispatch_sequence SET NOT NULL,

    ADD CONSTRAINT ck_fw_delivery_dispatch_event_id
        CHECK (btrim(current_event_id) <> ''),

    ADD CONSTRAINT ck_fw_delivery_dispatch_operation
        CHECK (current_operation IN ('DELIVERY', 'REMOVAL')),

    ADD CONSTRAINT ck_fw_delivery_dispatch_sequence
        CHECK (dispatch_sequence > 0),

    ADD CONSTRAINT ck_fw_delivery_removal_requires_success
        CHECK (
            removal_status = 'NOT_REQUESTED'
            OR delivery_status = 'SUCCEEDED'
        ),

    ADD CONSTRAINT ck_fw_delivery_dispatch_state
        CHECK (
            (
                current_operation = 'DELIVERY'
                AND removal_status = 'NOT_REQUESTED'
            )
            OR
            (
                current_operation = 'REMOVAL'
                AND delivery_status = 'SUCCEEDED'
                AND removal_status <> 'NOT_REQUESTED'
            )
        ),

    ADD CONSTRAINT uq_fw_delivery_current_event
        UNIQUE (tenant_id, current_event_id);
