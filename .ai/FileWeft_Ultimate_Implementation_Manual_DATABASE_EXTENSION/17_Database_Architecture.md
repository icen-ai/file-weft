
# FileWeft Database Architecture

## Design Goals

Database layer must support:

- single tenant
- multi tenant SaaS
- document lifecycle
- version management
- workflow
- audit
- sync
- AI task
- diagnosis
- recovery


## Database Principles

1. Business tables contain tenant_id.
2. Domain ID is not database auto increment.
3. External IDs are isolated.
4. History data is never overwritten.
5. Operational tables are append oriented.


Recommended:

PostgreSQL primary deployment.

Compatible:

MySQL 8.


## ID Strategy

Use:

Snowflake ID or ULID.

Never expose database sequence as business identifier.
