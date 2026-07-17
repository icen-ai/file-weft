# FlowWeft Agent Workflow Tools

This module exposes Workflow application use cases to the provider-neutral Agent runtime without
giving Agent code a repository, engine, persistence handle, or raw domain mutation interface.

## Compatibility and public directory

`WorkflowAgentToolCatalog` remains the compatible 17-tool v1 contract. Its descriptors, command
envelope, executor and explicit legacy application ports are unchanged.

`WorkflowAgentPublicToolDirectory` is the additive FlowWeft 1.0 directory. It deterministically
projects every entry from `WorkflowWebRoute.all()` and therefore covers the public definition,
instance/history, task/form, collaboration, incident, migration, doctor and capability use cases.
The directory has its own provider, capability, version and digest. Directory discovery is metadata
only and never grants permission.

`WorkflowAgentPublicApplicationPortRegistry` registers only the existing framework-neutral public
Workflow application ports. A missing category port makes its tools unavailable to model discovery
and appears as `APPLICATION_PORT_UNAVAILABLE` in the safe registry snapshot. It is not represented
as an empty successful result.

All Agent envelopes, including READ operations, intentionally require `expectedResourceVersion`
and `idempotencyKey`. This is stronger Agent authorization/replay evidence and is not a claim that
HTTP GET routes require the HTTP mutation headers. READ bindings do not create or pass
`WorkflowWebWritePreconditions` to the application port.

## Secure invocation flow

1. Discover descriptors from the application-port registry, not from the full directory.
2. Resolve a plan with `WorkflowAgentPublicToolPlanResolver`. The canonical command fixes the
   operation, resource type/id/version, purpose, complete payload, idempotency key and execution
   nonce. Tenant and actor fields are not accepted.
3. Let the normal Agent runtime perform preflight authorization, policy, optional confirmation,
   execution recheck, durable one-time context consumption, final authorization and dispatch-fence
   consumption.
4. Immediately before application dispatch, call
   `WorkflowAgentPublicApplicationPortRegistry.bind(executable, now)`. The binding revalidates the
   exact descriptor and arguments, current tenant/principal, authorization revision and expiry,
   budgets, one-time receipts and confirmation ownership.
5. A category adapter decodes `binding.payload` into the existing typed Workflow Web API DTO and
   invokes the registered public application port with `binding.trustedContext`,
   `binding.resourceId` and, for writes, `binding.writePreconditions`.

The category adapter must not accept tenant, authenticated actor, authorization evidence, route
resource identity, optimistic-lock version or idempotency key from the payload. Those values come
only from the permission-bound invocation. The public application port must still perform fresh
application authorization and current-user filtering on every call, including idempotent replay.

High-risk publishing, lifecycle controls, task decisions/delegation/add-sign/return, incident
repair actions and migration execution require a confirmation issued and approved by the same
current principal. Agent initiation never supplies administrator or superuser semantics.

This directory/registration slice intentionally does not add a second generic executor. The
existing executor remains the compatible mutation path; concrete category adapters can be added
incrementally while reusing the public Workflow application contracts. For list, doctor,
capability and other collection operations, hosts should use a stable tenant-relative scope value
as `resourceId` so authorization and idempotency evidence still bind a concrete target.

## Remaining FW10-035 closure

This slice makes all 33 current public use cases deterministic and discoverable and provides their
final permission binding. It does **not** claim that those 33 use cases are all executable through
the new public directory yet. FW10-035 remains in progress until the following are implemented:

- category adapters that strictly decode each canonical payload into the matching typed public Web
  application command/query and dispatch exactly one registered port method;
- bounded, redacted result encoding from every `WorkflowWebApplicationResult` shape back into an
  Agent tool result;
- end-to-end tests for authorization revocation between planning and dispatch, one-time execution
  context and dispatch-fence replay, idempotent application replay, cross-tenant hiding and
  high-risk confirmation expiry/ownership.
