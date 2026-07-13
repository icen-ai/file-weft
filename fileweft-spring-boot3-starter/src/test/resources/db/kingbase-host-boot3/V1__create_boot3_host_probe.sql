CREATE TABLE fw_boot3_host_migration_probe (
    probe_id varchar(64) PRIMARY KEY,
    runtime_name varchar(64) NOT NULL
);

INSERT INTO fw_boot3_host_migration_probe (probe_id, runtime_name)
VALUES ('spring-boot-host', 'boot3-flyway11');
