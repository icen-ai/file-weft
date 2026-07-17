# FlowWeft Capacity Runtime

`flowweft-capacity-runtime` is the provider-neutral orchestration layer for the
contracts in `flowweft-capacity-api`. It does not own a database, queue, storage
SDK, connector, Agent budget, or workflow lease. Hosts supply those concerns
through the runtime ports.

## Guarantees

- Every operation obtains a host-authenticated `CapacityTrustedContext` for the
  exact purpose and rechecks tenant, principal, authorized scope, freshness and
  deadline. Request tenant IDs are never accepted as identity.
- Admission resolves a complete `system -> tenant -> provider -> resource`
  policy view, verifies explicit empty-layer coverage, compares that resolution
  with the provider snapshot, and submits exact policy/state CAS evidence.
- `WorkloadKind` remains open. Upload, indexing, Agent, workflow and custom jobs
  all use the same guards; the runtime contains no workload-specific bypass.
- Only allow-listed, capacity-only degradation capabilities reach a provider.
  Authentication, authorization, tenant isolation, auditing and security checks
  cannot be degraded.
- A raw idempotency key is SHA-256 hashed as the first service action. Only its
  digest crosses API/provider boundaries; no result, diagnostic or `toString()`
  retains the raw value.
- The provider's atomic CAS, idempotency record, usage update and fenced lease
  transition are authoritative. The runtime never performs a local
  check-then-write reservation.
- Admission, renewal and release call the mutation SPI once. Transport failure,
  malformed success, or provider revision drift after that call produces
  `OUTCOME_UNKNOWN`, never an automatic retry.
- An unknown result includes a raw-key-free, digest-bound
  `CapacityUnknownOutcomeReference` with the authorized tenant/principal/target
  identity needed for a fresh authorization check.
  `runtime.reconciliation.reconcile(...)` sends that exact scope and binding to
  the read-only `CapacityOutcomeReconciliationPort`; it never calls `admit`,
  `renew` or `release`. The result is canonical `APPLIED`,
  `CONFIRMED_NOT_APPLIED`, or `STILL_UNKNOWN` evidence.
- Reconciliation requires the dedicated open purpose
  `CapacityRuntimePurposes.RECONCILIATION` (`capacity.reconcile`). A separately
  authorized operations service principal may investigate an original user's
  outcome after that user is revoked or leaves, but it must belong to the same
  tenant and hold fresh authority over the exact target. The original principal
  remains immutable evidence in the reference and never becomes the worker's
  mutation authority.
- Descriptor configuration/capability drift is checked before and after every
  provider call. Drift after a possible mutation is classified as unknown
  outcome because the mutation may already be durable.
- Lease renewal/release is tenant/principal scoped and uses the provider's state
  version plus fencing token. Expired leases fail before provider invocation;
  stale fences must be rejected atomically by the provider.
- Metrics contain categorical bands, outcome codes and evidence digests only.
  They are scheduled through `CapacityAfterCommitSignalPort`; tenant/resource
  IDs, raw counts, secrets and provider messages are not metric labels.

## Transaction boundary

Every authorization-dependent policy/provider/reconciliation call first invokes
`CapacityExternalCallBoundary.requireOutsideTransaction`. Spring/JTA adapters
must throw while a database transaction is active. This keeps network calls and
signals out of business transactions. Use
`UNMANAGED_NON_TRANSACTIONAL` only in a host that has no ambient transaction
facility and can prove calls are already outside one.

## Host ports

| Port | Required behavior |
| --- | --- |
| `CapacityTrustedContextProvider` | Return current host identity and fresh exact authorization evidence for the requested purpose. Never build it from body/query tenant fields. |
| `CapacityPolicySource` | Return an exact time-bounded snapshot, including every checked hierarchy level. Missing coverage fails closed. |
| `CapacityProviderRegistry` | Resolve immutable provider identity; the live descriptor is checked per call. |
| `CapacityProviderSpi` | Atomically enforce policy/state CAS, digest idempotency, usage mutation and fencing; contain exceptions behind code-only results. |
| `CapacityOutcomeReconciliationPort` | Query the exact original scope/binding without invoking a mutation and return canonical or negative evidence. |
| `CapacityExternalCallBoundary` | Reject calls made inside a database transaction. |
| `CapacityMetricSink` / `CapacityAfterCommitSignalPort` | Aggregate low-cardinality evidence and schedule delivery only after transaction completion. |

## Composition

```kotlin
val runtime = CapacityRuntime(
    trustedContexts = hostTrustedContexts,
    policies = hostPolicySource,
    providers = ImmutableCapacityProviderRegistry(mapOf(providerId to providerImplementation)),
    outcomeReconciliation = hostOutcomeLookup,
    externalCalls = hostTransactionBoundary,
)

val decision = runtime.admission.admit(
    CapacityGuardCommand(
        providerId,
        resourceScope,
        WorkloadKind("custom.pipeline"),
        listOf(CapacityDemand(CapacityDimension.QUEUE_DEPTH, 1L)),
        emptySet(),
        2_000L,
    ),
    rawIdempotencyKey,
)
```

`ADMIT` and `DEGRADE` only reserve capacity; the caller must still perform fresh
business authorization before executing its work. `THROTTLE` and `REJECT` are
explicit outcomes and never hide a reservation.

If `decision.errorCode == OUTCOME_UNKNOWN`, persist the returned reference and
invoke only the reconciliation service. Do not generate a new idempotency key or
blindly repeat the mutation. Provider adapters must retain exact idempotency
scope/binding lookup evidence for at least their documented reconciliation
window.
