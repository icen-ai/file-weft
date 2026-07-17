CREATE TABLE fw_governance_deletion_target_manifest
(
    id                          varchar(64) PRIMARY KEY,
    tenant_id                   varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    preparation_digest          varchar(64) NOT NULL,
    planning_request_digest     varchar(64) NOT NULL,
    planning_identity_digest    varchar(64) NOT NULL,
    resource_reference_digest   varchar(64) NOT NULL,
    assessment_digest           varchar(64) NOT NULL,
    stage_code                  varchar(128) NOT NULL,
    target_reference            varchar(512) NOT NULL,
    target_reference_digest     varchar(64) NOT NULL,
    target_revision             varchar(256) NOT NULL,
    target_digest               varchar(64) NOT NULL,
    target_binding_digest       varchar(64) NOT NULL,
    manifest_digest             varchar(64) NOT NULL,
    memento_version             integer NOT NULL,
    manifest_memento            longblob NOT NULL,
    manifest_memento_digest     varchar(64) NOT NULL,
    created_time                bigint NOT NULL,
    updated_time                bigint NOT NULL,
    CONSTRAINT uq_fw_gov_target_manifest_preparation
        UNIQUE (tenant_id, preparation_digest),
    CONSTRAINT uq_fw_gov_target_manifest_reference
        UNIQUE (tenant_id, target_reference_digest),
    CONSTRAINT ck_fw_gov_target_manifest_version CHECK (memento_version = 1),
    CONSTRAINT ck_fw_gov_target_manifest_size
        CHECK (OCTET_LENGTH(manifest_memento) BETWEEN 1 AND 4194304),
    CONSTRAINT ck_fw_gov_target_manifest_time
        CHECK (created_time >= 0 AND updated_time = created_time)
) ENGINE=InnoDB;

CREATE INDEX idx_fw_gov_target_manifest_resource
    ON fw_governance_deletion_target_manifest
       (tenant_id, resource_reference_digest, stage_code);

CREATE TABLE fw_governance_deletion_item_operation
(
    id                          varchar(64) PRIMARY KEY,
    tenant_id                   varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    operation_key_digest        varchar(64) NOT NULL,
    preparation_digest          varchar(64) NOT NULL,
    plan_digest                 varchar(64) NOT NULL,
    step_digest                 varchar(64) NOT NULL,
    target_binding_digest       varchar(64) NOT NULL,
    manifest_digest             varchar(64) NOT NULL,
    item_binding_digest         varchar(64) NOT NULL,
    provider_id                 varchar(128) NOT NULL,
    provider_revision           varchar(256) NOT NULL,
    operation_reference         varchar(512) NOT NULL,
    operation_reference_digest  varchar(64) NOT NULL,
    operation_status            varchar(128) NOT NULL,
    operation_version           bigint NOT NULL,
    state_digest                varchar(64) NOT NULL,
    memento_version             integer NOT NULL,
    operation_memento           longblob NOT NULL,
    operation_memento_digest    varchar(64) NOT NULL,
    created_time                bigint NOT NULL,
    updated_time                bigint NOT NULL,
    CONSTRAINT uq_fw_gov_item_operation_key UNIQUE (tenant_id, operation_key_digest),
    CONSTRAINT uq_fw_gov_item_operation_ref UNIQUE (tenant_id, operation_reference_digest),
    CONSTRAINT fk_fw_gov_item_operation_manifest
        FOREIGN KEY (tenant_id, preparation_digest)
        REFERENCES fw_governance_deletion_target_manifest (tenant_id, preparation_digest),
    CONSTRAINT ck_fw_gov_item_operation_version CHECK (operation_version > 0),
    CONSTRAINT ck_fw_gov_item_operation_status CHECK
        (operation_status IN ('prepared', 'started', 'verified-absent', 'outcome-unknown', 'permanent-failure')),
    CONSTRAINT ck_fw_gov_item_operation_memento_version CHECK (memento_version = 1),
    CONSTRAINT ck_fw_gov_item_operation_memento_size
        CHECK (OCTET_LENGTH(operation_memento) BETWEEN 1 AND 4194304),
    CONSTRAINT ck_fw_gov_item_operation_time
        CHECK (created_time >= 0 AND updated_time >= created_time)
) ENGINE=InnoDB;

CREATE INDEX idx_fw_gov_item_operation_plan
    ON fw_governance_deletion_item_operation
       (tenant_id, plan_digest, operation_status);
