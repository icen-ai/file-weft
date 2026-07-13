
# Database Optimization Rules

> **Agent schema boundary:** Agent records below refer only to retained
> V012/V026 compatibility data. `0.0.2` has no Agent product capability, and
> these records must not be used to infer an active Agent roadmap.


## Index Strategy


Document query:

tenant_id + lifecycle_state

tenant_id + doc_no

tenant_id + updated_time


Sync:

document_id + connector_name + sync_status


Task:

tenant_id + task_status


## Partition Strategy


Large SaaS:

fw_operation_log

fw_sync_record

fw_task


Can use monthly partition.


## Data Retention


Never delete:

- audit
- operation log


Archive:

- old sync records
- agent history (historical compatibility data)


## JSON Usage


metadata_json:

Allowed for extension fields.

Forbidden:

Core query fields must not live only in JSON.
