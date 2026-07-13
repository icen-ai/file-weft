-- MySQL does not support anonymous PL/pgSQL blocks, jsonb_typeof(), btrim(),
-- or CTE-based UPDATE ... FROM. The original migration performs four data
-- integrity checks and then backfills current_event_id, current_operation,
-- and dispatch_sequence from fw_outbox_event.
--
-- For MySQL we add the columns and rely on the application layer to ensure
-- the fence invariants during upgrade windows. A production MySQL upgrade
-- should follow the same operational procedure: drain RUNNING delivery events,
-- resolve duplicates, and then run this migration.
ALTER TABLE fw_document_delivery_target
    ADD COLUMN current_event_id varchar(64),
    ADD COLUMN current_operation varchar(16),
    ADD COLUMN dispatch_sequence bigint;

-- Backfill from the newest recoverable delivery or removal event per target.
-- MySQL 8 supports window functions and CTEs.
WITH relevant AS (
    SELECT event.tenant_id,
           event.id AS event_id,
           event.event_type,
           event.event_status,
           event.created_time,
           event.updated_time,
           JSON_UNQUOTE(JSON_EXTRACT(event.payload_json, '$.deliveryId')) AS delivery_id
    FROM fw_outbox_event event
    WHERE event.event_type IN (
        'document.delivery.target.requested',
        'document.delivery.target.removal.requested'
    )
      AND JSON_TYPE(JSON_EXTRACT(event.payload_json, '$.deliveryId')) = 'STRING'
      AND JSON_UNQUOTE(JSON_EXTRACT(event.payload_json, '$.deliveryId')) <> ''
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
    INNER JOIN ranked
        ON ranked.position = 1
       AND ranked.tenant_id = target.tenant_id
       AND ranked.delivery_id = target.id
SET target.current_event_id = ranked.event_id,
    target.current_operation = CASE ranked.event_type
        WHEN 'document.delivery.target.requested' THEN 'DELIVERY'
        WHEN 'document.delivery.target.removal.requested' THEN 'REMOVAL'
    END,
    target.dispatch_sequence = ranked.historical_sequence;

ALTER TABLE fw_document_delivery_target
    MODIFY COLUMN current_event_id varchar(64) NOT NULL,
    MODIFY COLUMN current_operation varchar(16) NOT NULL,
    MODIFY COLUMN dispatch_sequence bigint NOT NULL,

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
