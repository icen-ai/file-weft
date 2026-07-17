# FlowWeft Governance JDBC Persistence

This module is the Spring-free, JDK 8 durable store for the FlowWeft 1.0
governance runtime. `JdbcGovernancePersistence` implements the deletion-run
repository, durable outbox repository, and a value-free Doctor source for
PostgreSQL, MySQL 8, and KingbaseES. `JdbcGovernanceDeletionTargetLedger`
persists immutable pre-plan target manifests and exact per-item operation
checkpoints for the same three dialects.

## Composition

Construct the store from a managed `DataSource` and pass the same instance to
`ProviderNeutralGovernancePlanningRuntime`,
`ProviderNeutralGovernanceDeletionWorker`, `GovernanceOutboxRelay`, and
`ProviderNeutralGovernanceDoctor`. An optional `GovernanceMetricsPort` receives
only the existing low-cardinality governance metric codes; tenant, principal,
resource, plan, worker, and provider values never become metric labels.

Retention policies, legal-hold catalogs, resource metadata, and principals stay
host-owned behind their existing ports. This module persists only the exact
immutable evidence embedded in an accepted deletion run; it does not introduce
shadow CRUD for those host systems.

Every lookup includes an explicit `tenant_id` predicate. Plan, record,
idempotency, and worker-claim values are indexed only by tenant-bound SHA-256
digests; exact decoded values are checked after lookup to reject collisions. Canonical run
mementos necessarily retain the exact trusted contracts needed after restart;
they are bounded, versioned binary records, never Java serialization, JSON,
provider payloads, credentials, exception text, URLs, or object keys. Operators
must apply the same database encryption and access controls as other governance
records.

## Migration

Apply V041 and then V042 from the shared FlowWeft workflow migration history
for the selected dialect. `GovernanceJdbcMigrations.resourcePath(...)` remains
the V041 compatibility entry; new embedders that do not scan the location use
the ordered `resourcePaths(...)` result.

- `ai/icen/fw/workflow/db/migration/postgres/V041__persist_governance_runtime.sql`
- `ai/icen/fw/workflow/db/migration/mysql/V041__persist_governance_runtime.sql`
- `ai/icen/fw/workflow/db/migration/kingbase/V041__persist_governance_runtime.sql`

V042 resources are:

- `ai/icen/fw/workflow/db/migration/postgres/V042__persist_governance_deletion_targets.sql`
- `ai/icen/fw/workflow/db/migration/mysql/V042__persist_governance_deletion_targets.sql`
- `ai/icen/fw/workflow/db/migration/kingbase/V042__persist_governance_deletion_targets.sql`

The migration creates only `fw_governance_deletion_run` and
`fw_governance_deletion_outbox`. A run CAS and its outbox record commit in one
serializable transaction. Outbox workers claim ready records with a monotonic
fencing token and bounded lease; acknowledgements require the exact tenant,
claim digest, worker digest, fence, record digest, and unexpired lease.
V042 creates the target manifest and item-operation tables. Every operation is
tenant-qualified and foreign-keyed to its manifest; operation transitions use
the exact version and canonical state digest as a compare-and-set fence.

## Failure semantics

The persistence layer retries at most once and only when the database explicitly reports that the
whole local transaction rolled back (`40001`, `40P01`, or MySQL error 1213). If
`Connection.commit()` loses its acknowledgement, `compareAndSet` returns `OUTCOME_UNKNOWN`; the runtime then
uses its existing read-only exact-state reconciliation. Claim and acknowledgement
commit uncertainty is surfaced to the relay as failure and is never converted
into a second mutation. Mementos are capped at 4 MiB by the migration and bounded stream reads;
Memento SHA-256, canonical state/receipt digests, row
identity, tenant, plan, version, and status are all checked during reads.

## Verification boundary

Focused tests cover bounded canonical decoding, transaction commit uncertainty,
tenant-qualified SQL, CAS/fencing statements, migration parity, and the Java
composition surface. Release evidence must additionally run
`postgresIntegrationCheck`, `mysqlIntegrationCheck`, and
`kingbaseIntegrationCheck` against isolated real databases. Success on one
dialect is not evidence for either of the others.
