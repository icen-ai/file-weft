# FlowWeft Capacity API

`flowweft-capacity-api` is the provider-neutral, Java 8 public contract for FW10-051 capacity,
admission control and backpressure. It contains no Spring, persistence, queue implementation,
provider SDK, network address, credential or raw workload payload.

## Boundary

The module defines:

- host-created `CapacityTrustedContext` evidence binding tenant, principal, purpose, exact authorized
  resource scope, authentication/authorization revisions and a finite authorization lifetime;
- versioned `CapacityPolicy`, hierarchical `ResourceScope` and open `WorkloadKind` machine codes for
  upload, synchronization, indexing, Agent, Workflow, storage, retrieval, connectors and extensions;
- standard dimensions and units for disk/stored/in-flight bytes, queue depth, concurrency,
  operations per second and bytes per second;
- immutable usage, reservation, limit, warning-watermark and critical-watermark snapshots;
- deterministic system -> tenant -> provider -> resource resolution. Every effective limit and
  watermark is the minimum of all applicable layers. A degradation is allowed only by the
  intersection of all applicable policies;
- digest-only idempotency, exact policy/state CAS, atomic `ADMIT`, `THROTTLE`, `REJECT` and
  `DEGRADE` decisions, fenced reservation leases, renewal and release receipts;
- a provider SPI whose failures are closed error codes rather than exceptions or raw messages;
- value-free Doctor and metric evidence. These contracts expose categorical codes, pressure bands
  and evidence digests, never measured values, tenant/resource labels, URLs, secrets or payloads.

`ADMIT` and `DEGRADE` always carry a reservation lease created by the same atomic transition.
`THROTTLE` never reserves and always carries a positive `retryAfterMillis`. `REJECT` is definitive
for the exact request and has no implicit retry. `DEGRADE` is possible only when the caller opted
into named capacity-only capabilities and every applicable policy permits them. Authorization,
tenant filtering, audit, encryption, integrity and other security controls are never degradation
capabilities; if a required control cannot run, the provider rejects the request.
A capacity lease proves only that shared capacity was reserved. It never authorizes the workload;
the owning application must continue to enforce its normal fresh business authorization.

## Relationship to existing components

- `ConnectorInvocationExecutor` remains the existing process-local bounded executor and circuit
  protection. A future runtime adapter may consult this API before submitting connector work, but
  this module neither replaces nor reaches into its queue.
- `AgentBudget` remains the immutable ceiling for one Agent run (tokens, calls, duration and cost).
  It is not shared-capacity evidence. A future Agent adapter must satisfy both `AgentBudget` and an
  atomic capacity decision; neither may weaken the other.
- Background task, outbox, Workflow effect/notification and Agent durable leases keep their current
  ownership/fencing semantics. A capacity reservation is an additional shared-resource lease, not
  a replacement for a job-delivery lease.
- `flowweft-observability` already owns system-wide Doctor aggregation. A later adapter can map the
  value-free capacity Doctor signals into its existing CAPACITY/DISK/queue slots.

## Provider requirements

Implementations must revalidate current authorization for every call and idempotent replay. They
must atomically resolve the strictest hierarchy, compare the expected policy digest and state
version, deduplicate the tenant/principal/operation-scoped idempotency digest, update usage, and
issue or fence a lease. Stale policy/state, expired authorization/lease, unsupported units and
provider failures return `CapacityProviderErrorCode`; implementations must not infer authorization
or a safe degradation from provider availability.

## Remaining FW10-051 work

This is an API slice, not completion of FW10-051. Runtime policy storage/resolution, distributed
atomic counters and rate windows, durable reservation persistence/reconciliation, connector,
upload, sync, indexing, Agent, Workflow and storage adapters, starter configuration, System Doctor
integration, dashboards/alerts, load generators, public benchmark environments and declared
capacity/backpressure limits remain to be implemented and verified. The delivery ledger must stay
in progress until those components and multi-tenant concurrency/failure tests are complete.
