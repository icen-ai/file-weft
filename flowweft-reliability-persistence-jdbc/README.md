# FlowWeft Reliability JDBC Persistence

Production JDBC persistence for the provider-neutral reliability runtime. The module has no
Spring, ORM, Flyway, or database-driver dependency. A host supplies a managed `DataSource`, runs
the V040 migration through its existing workflow migration history, and composes
`JdbcReliabilityPersistence` into the reliability runtime ports.

The adapter provides:

- atomic run intent plus outbox creation and exact idempotent replay;
- tenant-qualified reads and version/fencing-token compare-and-set transitions;
- monotonically fenced run, SLO schedule, and outbox leases;
- immutable provider-attempt and provider-result evidence rows;
- exact outcome-unknown persistence and read-only reconciliation evidence;
- atomic SLO schedule/evaluation/outbox transitions; and
- bounded, versioned canonical binary mementos (never Java serialization).

Logical IDs are tenant-scoped. PostgreSQL, MySQL, and Kingbase therefore use composite
`(tenant_id, id)` primary keys; MySQL additionally pins tenant comparison to `utf8mb4_bin` and
opaque IDs to `ascii_bin` so database collation cannot weaken the runtime's exact-string tenant
boundary. Unique-key conflicts are reconciled in a fresh transaction and never inferred from
vendor-specific affected-row counts.

Each public repository call opens one short local transaction and returns before authorization,
provider, observation, or worker signaling code runs. A connection failure while committing a
write is reported as `OUTCOME_UNKNOWN`; the caller must use the runtime's exact read-only lookup
or reconciliation path and must never blindly repeat a provider mutation.

The transaction layer retries only one database signal that guarantees the whole transaction was
rolled back (`40001`, `40P01`, or MySQL error 1213). It never retries an unknown commit. Mementos
are capped at 8 MiB by both migration checks and bounded JDBC stream reads before decoding.

Supported production dialects are PostgreSQL, MySQL 8, and KingbaseES. Unit tests and SQL-resource
contracts are not substitutes for the repository's real `postgresIntegrationCheck`,
`mysqlIntegrationCheck`, and `kingbaseIntegrationCheck` evidence.
