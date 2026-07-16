# FlowWeft Capacity JDBC Persistence

This module is the Spring-free, JDK 8 production persistence backend for the
FlowWeft 1.0 capacity runtime. It supports PostgreSQL, MySQL 8 and KingbaseES.

## Composition

Create one `JdbcCapacityProvider` with a managed `DataSource`, a stable provider
identifier and a SHA-256 configuration digest. The same instance implements:

- `CapacityProviderSpi`
- `CapacityPolicySource`
- `CapacityOutcomeReconciliationPort`

Register that exact instance under its explicit provider identifier. Policy
administration uses `putPolicy(CapacityPolicyPutRequest)` and requires a trusted
`capacity.policy.manage` context; tenant identifiers never come from an
untrusted request field.

## Migrations

Run V039 from the existing FlowWeft workflow migration location for the active
dialect:

- `ai/icen/fw/workflow/db/migration/postgres/V039__persist_capacity_runtime.sql`
- `ai/icen/fw/workflow/db/migration/mysql/V039__persist_capacity_runtime.sql`
- `ai/icen/fw/workflow/db/migration/kingbase/V039__persist_capacity_runtime.sql`

The migration creates policy, policy-resolution cache, state, measures,
reservations, idempotency intent and outbox tables. Row identifiers are
tenant-bound SHA-256 values, and every runtime SQL lookup still carries an
explicit `tenant_id` predicate.

## Failure semantics

A mutation first commits a `PREPARED` intent. A second serializable transaction
atomically changes state/reservation, stores the canonical result, marks the
intent `APPLIED`, and appends outbox evidence. It is never automatically retried
after a connection or commit failure. Use the read-only reconciliation port:

- `APPLIED` returns the digest-verified original decision/receipt;
- `NOT_APPLIED` confirms that a known precondition failure made no mutation;
- `PREPARED` or an absent row remains `STILL_UNKNOWN`.

Mementos are bounded, versioned binary records. Java serialization, raw
idempotency keys, provider payloads, credentials and exception text are not
persisted.

## Verification boundary

Unit tests exercise codec reconstruction, transaction commit-unknown cleanup,
tenant-qualified SQL and migration parity without H2. Real evidence must run
the same migration and integration contract against PostgreSQL, MySQL 8 and
KingbaseES in isolated CNB jobs; one database is not evidence for another.
