# Asynchronous Attestation Compatibility and Migration

This note defines the additive electronic-signature and witness lifecycle contract in
`flowweft-workflow-spi`. It does not change the legal meaning of an attestation and does not claim
that any provider, certificate profile, or jurisdiction is legally sufficient.

## Compatibility

- `WorkflowAttestationSpi.kt`, `WorkflowElectronicSignatureProvider`, `WorkflowWitnessProvider`,
  `WorkflowElectronicSignatureRequest`, `WorkflowWitnessRequest`, their terminal result types, and
  the existing evidence hierarchy are unchanged.
- A synchronous provider can continue implementing only the historical one-shot SPI. No migration
  is required.
- Asynchronous support is opt-in through the new lifecycle providers. Capability absence means
  asynchronous dispatch, reconciliation, cancellation, or diagnostics is unsupported; callers
  must fail closed instead of guessing provider behavior.
- No artifact, table, migration, HTTP route, certificate format, or vendor SDK is introduced by
  this SPI change.

## Durable host state

An adopting runtime persists host-owned state outside this SPI. At minimum it retains:

1. the exact original typed request and its `requestDigest`;
2. the returned `WorkflowAttestationOperationRef` and `operationDigest`, when one is known;
3. every current-call `WorkflowProviderReceipt`;
4. the historical terminal `WorkflowElectronicSignatureResult` or `WorkflowWitnessResult` without
   rewriting its receipt or evidence;
5. a monotonic lifecycle state and the next reconciliation schedule.

The operation reference is opaque consistency metadata, never a URL, bearer token, private key,
certificate, provider secret, or substitute for current authorization. Provider credentials stay
in server-side configuration or secret handles and never enter browser storage.

## Dispatch and reconciliation rule

Before async dispatch, query `WorkflowAttestationCapabilityProvider` for the selected exact profile
and provider revision. Async use is safe only when the snapshot advertises bounded asynchronous
completion plus reconciliation by both original request digest and operation reference. Digest
reconciliation is required because a timeout can happen after the provider accepted a request but
before an operation reference reached FlowWeft.

The transition is:

```text
dispatch -> accepted -> pending -> completed | failed
            \             /
             outcome-unknown -> reconcile only
```

Any provider exception, transport interruption, deadline, or lost acknowledgement after dispatch
becomes `outcome-unknown` with `retryable=false`. Never blindly submit the original signature or
witness request again. Reconcile using the original typed request and, when available, the exact
operation reference. Repeated reconciliation calls use fresh call contexts but must preserve the
original tenant, provider id, provider revision, request digest, profile, statement, and actor
binding.

An `accepted` or `pending` receipt proves only that the current provider call succeeded. It is not
terminal signature/witness evidence. Completion reuses the original terminal result and
`WorkflowAttestationEvidence`; the lifecycle layer does not invent or translate a second evidence
model.

## Cancellation

Cancellation is a fresh provider request bound to the exact original request and operation. Only a
`cancelled` result confirms cancellation. `already-terminal`, `unsupported`, `failed`, and
`outcome-unknown` still require reconciliation of the original operation. A cancellation timeout
must not be retried blindly and must never be reported as proof that remote work stopped.

## Doctor and operational safety

`WorkflowAttestationDoctor` returns bounded machine codes, severity, and counts only. Implementations
must map vendor failures to an allowlisted value-free code and must not return exception text,
headers, endpoints, account ids, certificate bodies, signed URLs, or credentials. Doctor results
are diagnostics, not authorization and not legal attestation evidence.

## Adoption sequence

1. Keep the existing one-shot implementation active.
2. Add capability and value-free Doctor implementations for the same configured provider/profile.
3. Add durable host persistence and a reconciliation worker before enabling async dispatch.
4. Implement the async signature or witness provider and reconcile every accepted/pending/unknown
   result outside the business transaction.
5. Enable cancellation only when the capability snapshot advertises it and the host can continue
   reconciliation afterward.
6. Run provider contract tests for exact tenant/provider/revision/request/profile/statement/actor
   binding, timeout acknowledgement loss, digest-only reconciliation, operation reconciliation,
   cancellation uncertainty, and secret-safe diagnostics.

Persistence schema and runtime scheduling are intentionally not defined in this SPI module. They
are additive host/runtime responsibilities and require their own compatibility, migration, and
operational rollout.
