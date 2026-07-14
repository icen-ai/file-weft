-- Historical workflows deliberately remain null: their submitter cannot be
-- reconstructed unambiguously from optional audit or idempotency data.
ALTER TABLE fw_workflow_instance
    ADD COLUMN submitted_by varchar(256);

-- As with the other opaque host-user columns, the complete UTF-16 length and
-- invisible-character contract is enforced at FileWeft's trusted application
-- boundary for MySQL.
