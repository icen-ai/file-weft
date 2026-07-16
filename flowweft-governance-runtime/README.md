# FlowWeft Governance Runtime

`flowweft-governance-runtime` is the provider-neutral application orchestration layer for
`flowweft-governance-api`. It deliberately reuses the API module's retention, legal-hold,
secure-deletion, failure, capability, and Doctor contracts. It does not define a second
governance model and does not depend on Spring, ORM, a database driver, or a vendor SDK.

## Runtime guarantees

- Every entry point starts from a trusted host-supplied tenant, principal, purpose, exact
  resource revision/digest, idempotency key, and bounded call window.
- `GovernanceAuthorizedCallFactory` obtains fresh authorization for each exact purpose and
  delegates final tenant/principal/resource/revision/expiry validation to the governance API
  call context.
- Planning resolves legal hold before retention policy, target discovery, or provider work.
  Active, incomplete, stale, mismatched, or unavailable hold evidence fails closed.
- Executable plans contain exactly the API-defined seven stages in this order:

  1. `PERSIST_TOMBSTONE`
  2. `APPEND_DECISION_AUDIT`
  3. `ENQUEUE_PURGE_OUTBOX`
  4. `PURGE_INDEX_PROJECTIONS`
  5. `PURGE_OBJECT_CONTENT`
  6. `FINALIZE_METADATA`
  7. `APPEND_COMPLETION_AUDIT`

- The durable aggregate stores a contiguous prefix of successful API receipts. CAS version and
  state digest protect every transition. A state transition and its outbox record are one atomic
  repository operation.
- The worker executes at most one stage per call and refreshes legal-hold, retention, clock, and
  authorization evidence before a destructive dispatch.
- Index and object stages can only advance with the API's `VERIFIED_ABSENT` evidence.
- A durable `PROVIDER_CALL_STARTED` checkpoint is written before the provider call. Once that
  checkpoint exists the runtime never blindly resubmits the call.
- A timeout, malformed reply, lost acknowledgement, or crash after the started checkpoint becomes
  `OUTCOME_UNKNOWN`. Reconciliation uses only the original execution request, provider revision,
  attempt, receipt, and opaque operation reference. It cannot create a new operation.
- Metrics and Doctor findings contain only bounded, low-cardinality machine codes and counts.
  URL-shaped or otherwise value-bearing diagnostic codes are replaced with
  `diagnostic-source-invalid`.

## Transaction and asynchronous boundary

`GovernanceDeletionRepository.compareAndSet` must run one short local transaction that stores both
the candidate aggregate and the outbox row. Repository methods must return after closing that
transaction. They must never call authorization, policy, hold, deletion, reconciliation, or worker
ports.

`GovernanceOutboxRelay` claims rows in a short transaction, signals workers after that transaction
has returned, and then acknowledges the exact fenced claim in another short transaction. Host
implementations should use leases and monotonically increasing fencing tokens. A worker signal is
only a wake-up hint; the repository remains authoritative.

## Host integration

The host supplies implementations for:

- `GovernanceRuntimeAuthorizationPort`: fresh authorization snapshots from the host identity and
  policy system.
- `GovernanceRuntimeClockPort`: controlled current time plus auditable effective-clock snapshots.
- `GovernanceRuntimeIdPort`: opaque collision-resistant identifiers.
- `GovernanceRetentionPolicyPort` and the API legal-hold/evaluator ports.
- `GovernanceDeletionTargetPort`: exactly one opaque target for every fixed API stage.
- `GovernanceDeletionProviderRegistry`: stage executors and exact-operation reconcilers.
- `GovernanceDeletionRepository` and `GovernanceOutboxRepository`: durable CAS and outbox storage.
- `GovernanceWorkerSignalPort`, `GovernanceMetricsPort`, and optional value-free diagnostic sources.

No schema or vendor adapter is bundled here. A persistence adapter should serialize API/runtime
digests, plans, receipts, dispatch checkpoints, CAS versions, retry times, and outbox fencing data
without weakening their invariants. Provider credentials, bearer tokens, request headers, paths,
URLs, or provider error text must not be written into runtime failure, metric, or Doctor codes.

## Recovery rules

| Durable state | Allowed next action |
| --- | --- |
| `READY` | Refresh evidence, prepare the exact next stage, CAS checkpoint |
| `DISPATCH_PREPARED` | If loaded by a later worker, CAS-reset it and rebuild evidence, authorization, provider binding, and request before dispatch |
| `DISPATCH_STARTED` | Record `OUTCOME_UNKNOWN`; never execute again |
| `RETRY_WAIT` | Retry only after an explicit API `RETRYABLE_FAILURE` and due time |
| `RECONCILIATION_REQUIRED` | Reconcile the exact original operation only |
| `BLOCKED` | Refresh hold/retention evidence; resume only when clear and eligible |
| `COMPLETED` / `FAILED` | Terminal; no provider call |

If a CAS acknowledgement is unknown, the runtime rereads the exact aggregate and accepts it only
when its state digest matches the candidate. Otherwise it reports store outcome unknown and leaves
recovery to a later authoritative reread.

## Verification

The module tests cover legal-hold priority, exact seven-stage planning, idempotent replay, CAS
acknowledgement loss, transaction/provider separation, started-call recovery, exact reconciliation,
capability fail-closed behavior, value-free Doctor output, outbox transaction boundaries, and pure
Java consumption. The intended focused task, after the module is registered by the repository
integrator, is:

```powershell
.\gradlew.bat :flowweft-governance-runtime:test
```
