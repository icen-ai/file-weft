# FlowWeft Workflow SLA JDBC Persistence

This additive adapter implements `WorkflowSlaDurableStore` for PostgreSQL,
MySQL 8 and KingbaseES. It depends only on the provider-neutral SLA runtime and
JDBC; no Spring, ORM, calendar provider, identity service or notification
vendor is called from a database transaction.

The schema is normalized into tenant-scoped schedule and milestone rows. A
schedule row is the transaction lock and compare-and-set boundary. Each write
checks the expected schedule version, writes the canonical schedule/milestone
digests, and records the exact operation digest and base version. A commit
exception is considered successful only when a new connection rereads that
exact operation; otherwise the result is `outcome-unknown`.

Milestone leases use a monotonic durable `fence_sequence`. An expired lease
that never reached the provider-call checkpoint may be reclaimed safely with a
higher fence. Once `action-call-started` is durable, the adapter never retries
it automatically, so a crash cannot duplicate an escalation or reminder side
effect. An expired checkpoint remains visible in SLA diagnostics. The runtime
still needs an additive, authorization-guarded recovery command to classify a
crash-stranded checkpoint as outcome-unknown before its existing reconciliation
command can accept provider/operator evidence.

The owned migrations are V038 resources exposed by
`WorkflowSlaJdbcMigrations`. The standard `flowweft-workflow-persistence-jdbc`
migration line carries a byte-equivalent V038 copy, so the official migration
runner and CLI install it automatically after V037. A host using this adapter
without that standard migration module may add exactly one adapter-owned
dialect location to its own `flowweft_workflow_schema_history` Flyway line.
Neither path touches V001-V029 or the legacy document approval tables; never
configure both locations in the same Flyway run.

Focused H2 tests cover guarded creation, idempotent replay, ambiguous commit
reconciliation, durable receipt restoration, stale-fence rejection, expired
pre-checkpoint lease recovery, due ordering, diagnostics and tenant isolation.
The PostgreSQL, MySQL and Kingbase real-database lanes remain mandatory release
evidence because one dialect cannot prove another dialect's locking and DDL
behavior.
