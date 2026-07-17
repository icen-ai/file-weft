# FlowWeft Agent Web API

`flowweft-agent-web-api` is the Java 8, provider-neutral, framework-free public Web/Application
contract for the FlowWeft 1.0 Agent product surface (FW10-044). It contains no Spring controller,
JSON library, provider SDK, persistence implementation, repository or network client.

## Contract boundary

The module defines:

- a trusted host context whose tenant and principal reuse `AgentRunContext` and whose current
  authorization revision, expiry and evidence cannot come from a request body; its
  `trustedContextDigest` canonically binds tenant, principal, request, authentication, revision,
  expiry and the external authorization evidence digest;
- strong Agent ETags and idempotency preconditions for every mutation;
- a versioned `/flowweft/v1/agent` route catalog for conversations, durable runs, messages/events,
  cancellation, citations, confirmations, provider configuration, Doctor and evaluations;
- bounded cursor/page DTOs and payload-free durable event frames that a controller may expose via
  polling, long-polling or SSE without publishing Kotlin `Flow`;
- conversation/run projections that reuse `AgentRunStatus`, `AgentBudget`, `AgentUsage` and the safe
  `AgentRunFailure` contract;
- authorization-filtered citation evidence that reuses `AgentCitation`; its controlled factory
  requires the current trusted tenant and fresh authorization, then binds the citation, filter
  receipt, authorization decision and complete trusted-context digest into one evidence digest;
- confirmation inbox/detail/decision contracts that reuse the exact `AgentApprovalRequest` and
  `AgentApprovalDecision` ABI;
- secret-reference-only provider configuration, local capability snapshots and code-only Doctor
  diagnostics;
- evaluation dataset/trigger/run/result contracts that reuse the existing versioned suite,
  provider snapshot, evaluator reference and regression report types. Durable evaluation state is
  mapped to an open, transition-free Web status code so this API does not depend on runtime internals.

The only public message body is `authorizedDisplayText` for the current user's own conversation.
Application code must create it from an authorized, policy-redacted USER or ASSISTANT projection.
System/developer prompts, hidden chain-of-thought, retrieved document body, tool arguments/results,
provider payloads and diagnostic exception text are not representable by the output DTOs.

No provider configuration contract accepts an endpoint or URL. Provider connections and
credentials are administrator-managed opaque references. Secret material is never returned,
including immediately after an update.

## Application requirements

Implementations of the application ports must:

1. obtain `AgentWebTrustedContext` from verified host authentication and call `requireFresh` at the
   actual application operation;
2. authorize every read, write and idempotent replay for the current tenant/principal, returning
   `hidden()` for cross-tenant or non-visible objects;
3. translate commands into existing Agent application/runtime use cases rather than accessing an
   Agent durable store or repository directly;
4. scope raw idempotency keys by tenant/principal/action before hashing and persistence;
5. revalidate citations against current authoritative authorization before each response;
6. for confirmations, reload the authoritative request, call `requireCurrentFor`, bind the exact
   proposal/argument/evidence digest, authoritative request nonce and ETag, and atomically persist
   one approved or rejected decision. A stale authorization revision, expired request, different
   current principal, consumed request, changed nonce or changed arguments fails closed;
7. map failures to `AgentWebErrorCode` values and never derive response status or text from a
   provider exception;
8. resolve provider connection/credential references from administrator-managed inventories and
   reject unowned, disabled or mismatched references;
9. reload evaluation dataset, provider snapshot and evaluator reference server-side and require
   every submitted digest/version to match before triggering the existing evaluation runtime.

## Delivery status

This is an API contract slice, not completion of FW10-044. The delivery ledger must remain in
progress until runtime/application adapters, Spring/controller serialization, same-origin BFF,
console screens, localization/accessibility, streaming resume behavior and security E2E are
implemented and verified. Required E2E includes cross-tenant hiding, dynamic authorization
revocation, citation filtering, cursor recovery, cancellation, idempotent replay, concurrent and
expired confirmation decisions, secret non-disclosure and provider failure redaction.
