-- Historical workflows deliberately remain null: their submitter cannot be
-- reconstructed unambiguously from optional audit or idempotency data.
ALTER TABLE fw_workflow_instance
    ADD COLUMN submitted_by varchar(256);

-- Keep the same Kingbase compatibility policy as the other opaque host-user
-- columns: canonical UTF-16 length and unsafe characters are enforced at the
-- trusted FileWeft application boundary.
