# FlowWeft Reliability Runtime

`flowweft-reliability-runtime` is the provider-neutral durable orchestration layer for
`flowweft-reliability-api`. It has no Spring, ORM, database driver, scheduler implementation or
vendor SDK. JDBC persistence, provider adapters and starter wiring are separate modules.

## Runtime guarantees

- A host `ReliabilityTrustedInvocation` binds tenant, principal, purpose, action, exact resource,
  short dispatch window and a raw idempotency key. The raw key is immediately SHA-256 reduced and
  is never retained. `ReliabilityRuntimeAuthorizationPort` is queried authoritatively for initial
  submission, dispatch, cancellation, reconciliation, SLO evaluation and Doctor inspection.
- Recovery policy and topology are loaded from host sources. The runtime accepts a single-database,
  fileless Workflow topology; object storage and search index are never assumed. Every objective
  scope must exactly match the topology. Stable topology/provider bindings allow evidence lifetime
  renewal while detecting configuration, capability, revision and component drift.
- `ReliabilitySubmissionService` writes an immutable intent and outbox atomically before any
  provider mutation. Tenant-scoped digest idempotency replays the exact same intent and rejects the
  same key with different arguments.
- Every run transition uses CAS version, monotonically increasing lease fencing and an atomic
  outbox record. Repository methods are short local transactions and may not call authorization,
  topology, policy, metrics, signal or provider ports.
- The worker writes and verifies a durable `PROVIDER_CALL_STARTED` checkpoint containing the exact
  API request, provider binding and opaque operation reference before calling the provider. The
  provider call happens only after the repository transaction returns.
- A crash before that checkpoint is safely dispatchable. A crash, timeout, malformed result or lost
  acknowledgement after it becomes `RECONCILIATION_REQUIRED`; a later worker never repeats the
  mutation. Only `ReliabilityProviderSpi.reconcile` receives the exact original reference, and that
  SPI is read-only.
- Initial authorization remains a short dispatch capability. Backup, verification, restore and
  drill intents have their own digest-bound queue/execution deadline of up to seven days.
  Clean-target and immutable-manifest evidence must be fresh at exact dispatch; they do not keep a
  bearer authorization alive for hours.
- Cancellation before provider-call start is terminal. Cancellation after the checkpoint cannot
  claim that a side effect was stopped; it records cancellation requested and reconciles the exact
  original outcome. A deadline before dispatch is terminal; a deadline after dispatch is likewise
  reconciled rather than blindly retried.
- Provider revision/configuration/capability drift, topology drift, recovery-policy drift,
  authorization revocation and cross-tenant lookup fail closed before mutation.
- `ReliabilityOutboxRelay` claims and acknowledges with fencing; signaling occurs outside both
  transactions. Signals are wake-up hints only—the durable repository remains authoritative.
- `ReliabilitySloWorker` claims due schedules with leases, obtains fresh authorization and policy,
  reads the exact SLI window outside the repository transaction, computes fail-closed error budget
  and burn alerts, then atomically stores the evaluation and outbox. Missing provider data produces
  the API's critical data-unavailable alert.
- Runtime and API metrics contain closed categorical dimensions only. Doctor findings are bounded
  machine codes without tenant/resource/request labels, provider messages, URLs, secrets or values.

## Durable state recovery

| State | Allowed next action |
| --- | --- |
| `READY` | refresh auth/provider/topology/policy, then checkpoint and dispatch once |
| `PROVIDER_CALL_STARTED` | synthesize exact outcome-unknown evidence; never dispatch again |
| `RECONCILIATION_REQUIRED` | call only exact read-only provider reconciliation |
| `SUCCEEDED` / `FAILED` / `CANCELLED` / `TIMED_OUT` | terminal; no provider call |

If a repository acknowledgement is unknown, the runtime rereads the exact tenant/run and accepts it
only when the state digest equals the candidate. A different or absent state remains
`STORE_OUTCOME_UNKNOWN`; it is never manufactured into success.

## JDBC adapter contract

`ReliabilityRunRepository.createOrLoad` must atomically enforce
`(tenant_id, idempotency_digest)` uniqueness and store the first outbox. `claim` must atomically
increment the fencing token and CAS version. `compareAndSet` must require tenant, run id, expected
version and expected fence, then store candidate plus outbox in one transaction. The SLO repository
has the same claim/CAS/outbox rule. Persist all digests, exact provider operation references,
execution deadlines, cancellation flags, receipts and fencing values; never persist raw
idempotency keys, authorization tokens, credentials, provider messages or exceptions.

## Verification scope

Contract tests exercise exact replay/conflict, cross-tenant isolation, revoked authorization,
provider/topology drift, lease/CAS races, long execution deadlines, cancellation, crash before and
after provider dispatch, lost result persistence and exact reconciliation. The critical invariant is
that a crash after a mutation produces one mutation call total: any number of recovery attempts may
increase the reconciliation count, but mutation count remains exactly one. SLO tests exercise due
leases, missing-data critical alerts and atomic schedule/outbox persistence. Java tests consume the
runtime ports and durable models from outside the package.

After repository integration, the focused verification task is:

```powershell
.\gradlew.bat :flowweft-reliability-runtime:test
```
