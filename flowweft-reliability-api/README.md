# FlowWeft Reliability API

`flowweft-reliability-api` is the provider-neutral, Java 8 public contract for FlowWeft 1.0
reliability objectives, immutable backups, clean-target recovery, exact reconciliation and recovery
drills. It has no Spring, persistence implementation, scheduler, database driver, object-store SDK,
KMS SDK, network endpoint, credential, encryption key material, or vendor payload.

## Contract boundary

The module defines:

- integer-ppm SLI/SLO, exact observation-window binding, error-budget consumption and burn rate;
  missing, stale, insufficient and mismatched evidence all fail closed;
- RPO and RTO objectives for arbitrary component topologies. Database, object storage and search
  index are built-ins, but a Workflow-only single-database deployment is valid and no file, object
  storage or index component is assumed;
- an encrypted, immutable consistent-cut manifest. Each artifact binds one component, one shared cut,
  a content digest and a logical KMS/HSM key reference. Raw keys, wrapped-key bytes and credentials
  are deliberately unrepresentable;
- trusted tenant/principal/purpose/action/resource authorization evidence, digest-only idempotency,
  exact CAS fences and finite deadlines for create, verify, restore, reconcile and drill operations.
  The short authorization deadline covers intent creation/dispatch; a separate digest-bound async
  execution deadline (up to seven days) covers long backup, restore and drill work;
- restore only to a distinct `RECOVERY` environment and drill only in a distinct `DRILL`
  environment, both with a fresh exact-target clean proof. There is no in-place restore action;
- immutable-manifest verification receipts that must prove seal, artifact digests, encryption
  references, consistent cut and recovery-objective validation at the exact dispatch/start and bind
  that evidence with the immutable intent and CAS fence;
- exact outcome-unknown references. Reconciliation receives the original provider operation,
  request, idempotency context and CAS fence and is read-only; it cannot express a replacement
  payload or silently re-execute the operation;
- provider capabilities and asynchronous SPI operations, categorical Doctor evidence and
  value-free metrics whose only label dimensions are closed enums. Metrics cannot carry tenant,
  resource, provider, request or digest labels, measured values, messages or raw payloads.

Backup freshness is checked at manifest construction as
`consistentCut - componentRecoveryPoint <= componentRpo`. Restore and drill reports use the actual
failure/recovery reference bound into the request, not the historical backup cut: component RPO is
`recoveryReference - componentRecoveryPoint`, and component RTO is
`completion - recoveryReference`. Thus restoring an old but internally consistent backup today
cannot falsely report that today's RPO was met, and delay before the provider call counts against
RTO.

## Provider obligations

Implementations must revalidate fresh authorization and CAS at dispatch, including idempotent
replays; scope all state by the trusted tenant; atomically reserve the idempotency result before an
external mutation; and call external systems outside unrelated host database transactions. The
authorization token need not remain live for a multi-hour asynchronous execution: the immutable
intent, CAS fence, target isolation and execution deadline carry that execution. A
timeout after provider-call start is `OUTCOME_UNKNOWN`, never a retry signal. Only exact read-only
reconciliation may resolve it. Unsupported capabilities, stale descriptors, stale evidence, CAS
conflicts and provider failures are explicit closed failures and never imply a weaker fallback.

The host owns disaster declaration, promotion/cutover, traffic fencing, credentials, topology
inventory, provider configuration, encryption policy, retention policy, scheduling and durable
orchestration. This API neither declares a disaster nor promotes a recovery environment to
production.

## Architecture change control

1. **Why existing extension points are insufficient.** Storage, connector, Workflow and Agent SPIs
   operate one workload boundary; none can express a cross-component consistent cut, RPO/RTO clock,
   immutable verification receipt, clean-target proof or exact outcome-unknown reconciliation
   without leaking vendor behavior into application code.
2. **Why this addition is necessary.** FlowWeft 1.0 needs a common fail-closed recovery contract so a
   file deployment and a fileless Workflow deployment can prove the same recovery invariants while
   choosing different backup providers.
3. **Compatibility impact.** This is a new additive `flowweft-reliability-api` artifact under
   `ai.icen.fw.reliability.api`. It does not modify released `FileWeft*` ABI, existing SPIs, V001-V029,
   `fw_` tables or `/fileweft/v1`. Public APIs use Java-friendly classes, enums, collections and
   `CompletionStage`; no coroutine or vendor type crosses the ABI.
4. **Migration strategy.** Hosts first publish trusted environment/component topology and recovery
   policies, then install a provider adapter, run capability and Doctor checks, create and verify a
   backup, and prove restore in a separate recovery/drill environment. Existing backup jobs remain
   independent until their adapter can produce the full manifest and evidence; absence of an adapter
   is explicit `UNSUPPORTED`, not inferred success.
5. **Test plan.** Contract tests cover ppm overflow/rounding, missing/stale/mismatched SLI evidence,
   single- and multi-component manifests, RPO rejection, immutable seals, exact tenant/resource/CAS
   binding, future/stale clean proofs, in-place rejection, old-backup recovery clocks, RTO evaluation,
   exact reconciliation and Java source compatibility. Runtime/provider modules must additionally run
   fault-injection, database/object/index integration, crash-after-dispatch reconciliation, restore
   drills and environment-specific external acceptance gates.

## Delivery status

This artifact is the public API slice only. Durable orchestration/outbox state, provider registry,
policy storage, JDBC persistence, encryption and backup adapters, scheduling, starter wiring,
System Doctor aggregation, dashboards/alerts and real-environment backup/restore drills remain
separate FW10-052 delivery work and must not be inferred from this API's presence.
