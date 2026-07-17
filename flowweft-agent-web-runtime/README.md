# FlowWeft Agent Web Runtime

`flowweft-agent-web-runtime` is the framework-neutral application orchestration layer behind
`flowweft-agent-web-api`. It targets Java 8 and contains no Spring, HTTP/JSON library, ORM,
provider SDK or network client.

## Reused ABI

This module does not define another Agent run, approval, citation or evaluation model. It reuses:

- `AgentRunRequest`, `AgentRunSnapshot`, `AgentRunEvent` and `AgentCancellation` from
  `flowweft-agent-api`;
- the exact `AgentApprovalRequest` and `AgentApprovalDecision` evidence;
- `AgentCitation` plus `AgentWebCitationEvidenceDto.authorized(...)`;
- `AgentEvaluationSuite`, provider snapshots, evaluator references, durable evaluation state and
  regression reports;
- the public application DTOs and application ports in `flowweft-agent-web-api`.

The small `AgentWebRunUseCasePort`, `AgentWebApprovalUseCasePort` and
`AgentWebEvaluationUseCasePort` are provider-neutral bridges to existing Agent application/runtime
use cases. They expose neither an Agent repository nor a raw domain mutation interface.

## Mandatory operation order

Every public operation follows the same fail-closed order:

1. call `AgentWebTrustedContext.requireFresh` at the actual operation time;
2. obtain a current, binding-checked decision from `AgentWebAuthoritativeAuthorizationPort`;
3. use the resulting `AgentWebAuthorizedPersistenceScope` for tenant-scoped persistence;
4. return `hidden()` for a denied, cross-tenant, missing or otherwise invisible object;
5. for a mutation, immediately domain-separate and hash the raw idempotency key into a stable
   tenant/principal/action/aggregate scope; only that opaque SHA-256 token may enter a downstream
   request or durable intent, while a separate semantic command digest distinguishes exact replay
   from same-key/different-command conflict;
6. reserve the mutation, persist the canonical intent and append an outbox reference in one
   transaction;
7. leave the transaction, then invoke the existing Agent/approval/evaluation use case;
8. commit projections, CAS versions, mutation completion and the completion outbox event in a new
   transaction.

Repository adapters and the outbox adapter supplied to one application must enlist in the same
`AgentWebTransactionBoundary`. They must never execute callbacks, models, tools, providers or the
Agent coordinator while a database transaction is open.

Every authorized persistence scope carries the exact action, resource type/id/revision, purpose,
authorization-request binding digest, decision evidence, revision and lifetime. Adapters must
reject a method call whose expected resource does not exactly match that scope. Runtime journal,
aggregate CAS and external-intent helpers validate the complete record returned by the adapter;
an adapter integrity mismatch is never reported as invalid browser input or as success.

## Durable recovery and unknown outcomes

Run start, cancellation and evaluation trigger persist their exact canonical request in
`AgentWebExternalOperationRepository` before dispatch. Approval decisions are atomically consumed
in `AgentWebConfirmationRepository` before the approval use case is called. Outbox entries contain
safe identifiers and a payload-reference digest, not browser text, provider payloads or secrets.

If dispatch or the post-dispatch database commit is ambiguous, the mutation becomes
`OUTCOME_UNKNOWN`; the durable intent/decision and an outbox diagnostic remain the reconciliation
source. `AgentWebExternalOutcomeReconciler` processes one start, cancellation or evaluation
operation at a time. It obtains fresh host authorization, calls only the read-only
`AgentWebExternalOutcomeReconciliationPort` for the exact original derived operation, obtains fresh
authorization again, and atomically moves intent plus journal from unknown to a proven terminal
result while updating projections and outbox. An exception, stale observation, incomplete
evidence, CAS mismatch or another unknown answer leaves the operation unknown. The reconciler has
no start/cancel/trigger use-case dependency, so it cannot blind-retry the original mutation.

## Visibility and citations

Repository cursors and page membership must be calculated over the authorized scope only. A
repository returning another tenant's record fails the entire operation as hidden. Visible message
projection accepts only canonical USER text or canonical MODEL/A2A assistant text; system prompts,
developer prompts, tool arguments/results, retrieval bodies and arbitrary content extensions are
not displayable.

Each citation is separately authorized against its current document-version resource on every
response. Only then is `AgentWebCitationEvidenceDto.authorized(...)` called with the original
security-filter receipt and the new authoritative authorization decision. Cached authorization is
not a citation capability.

## Confirmations

Approve/reject performs an authoritative reload in the consumption transaction and calls
`AgentWebToolConfirmationDecisionCommand.requireCurrentFor(...)`. This binds the route request,
proposal, argument digest, request evidence, nonce, ETag/state version, tenant, current principal,
authorization revision and expiry. `consume(...)` is CAS and one-time; the resulting canonical
`AgentApprovalDecision` is what the existing approval use case receives.

## Provider configuration, Doctor and evaluation

Provider configuration contains only administrator-managed connection/credential references.
The runtime resolves ownership, provider identity, enabled state and exact local capability/model
support before storing a configuration; it has no endpoint or secret field.

Doctor ports are value-free: only component/status/reason codes and times may be returned. Provider
exceptions, prompts, endpoints, credentials and response bodies are forbidden.

Evaluation trigger reloads the dataset, provider snapshot and evaluator reference server-side and
requires the submitted versions/digests to match before creating the existing
`AgentEvaluationRunRequest` and invoking the durable evaluation coordinator bridge.

## Adapter obligations

- enforce tenant predicates in every query, CAS, idempotency lookup and cursor;
- encrypt or otherwise protect persisted user-visible message/intent content according to host
  policy;
- make mutation reservation, aggregate CAS and outbox append atomic;
- keep raw idempotency keys, credentials, endpoints and provider errors out of storage/logging;
- persist and index all three external intents by tenant and operation id and implement the exact
  load/CAS semantics used by the built-in reconciler; prepared records are never redispatched and
  unknown records become terminal only from authoritative query evidence;
- project only canonical Agent events and messages with their durable sequence/state version.
