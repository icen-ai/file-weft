CREATE TABLE fw_doctor_record
(
    id            varchar(64) PRIMARY KEY,
    tenant_id     varchar(64) NOT NULL,
    document_id   varchar(64) NOT NULL,
    task_id       varchar(64) NOT NULL,
    doctor_status varchar(32) NOT NULL,
    report_json   JSON        NOT NULL,
    created_time  bigint      NOT NULL,
    updated_time  bigint      NOT NULL,
    UNIQUE (tenant_id, task_id)
);

CREATE INDEX idx_fw_doctor_record_tenant_document_time
    ON fw_doctor_record(tenant_id, document_id, created_time DESC);
