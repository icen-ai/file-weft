# FlowWeft Agent Interoperability SPI

`flowweft-agent-interoperability-spi` is an additive, Java 8-friendly extension of
`flowweft-agent-api`. It does **not** define another MCP/A2A protocol stack.

## Why this module exists

The existing Agent API already owns the security-critical remote boundary:

- `AgentRemoteProtocolKind`, version baselines and transport bindings;
- trusted tenant/principal context and exact authorization scope;
- budgets, deadlines, cancellation and tenant-scoped idempotency;
- TLS-pinned, no-redirect network dispatch and reference-only credentials;
- MCP tool and A2A message/task operations;
- outcome-unknown handling and `AgentRemoteProtocolOutcomeReconciler`.

Duplicating those types would create two incompatible authorization paths. The one public gap useful
to FW10-025 is reviewed MCP resource/prompt metadata and a digest that binds that metadata to the
already-approved peer profile and observation. This module fills only that gap.

## Public contracts

- `McpResourceDescriptor` carries an opaque host-owned resource ID, revision, media type, limits,
  and descriptor/locator/content digests. A remote URI is never a field.
- `McpPromptDescriptor` carries an opaque prompt ID, versions, limits, and schema/message digests.
  Prompt text and rendered messages are never descriptor fields.
- `McpCatalogSnapshot` binds resource and prompt catalogs to an exact
  `AgentRemotePeerProfile`, `AgentRemotePeerObservation`, MCP `2025-11-25`, Streamable HTTP, and
  the existing tool-catalog digest.
- `AgentInteroperabilityCapabilitySnapshot` reuses the profile's extensible
  `AgentCapabilityId` set and adds the MCP catalog digest. A capability change therefore changes
  the snapshot digest and requires host review. Its `providerId` is the local provider that vouches
  for the snapshot; `profile.peerId` is the remote peer. They may differ and are separately
  digest-bound.
- `AgentInteroperabilityDispatchEvidence` accepts only a successful existing remote dispatch with
  matching TLS receipt and peer observation. `AgentInteroperabilityDispatchBinding` adds the
  reviewed extension snapshot to a later existing dispatch without changing its protocol ABI.
- `AgentInteroperabilityCapabilityProvider` and `AgentInteroperabilityDoctor` are asynchronous,
  provider-neutral ports. Doctor output contains stable codes, counts and evidence digests only.

All collections are defensive immutable snapshots. Public APIs use classes, enums, interfaces,
`CompletionStage`, and `@JvmStatic` factories; they expose no `suspend`, Flow, value class, sealed
interface, Spring type, SDK type, network client, raw request header, cookie, bearer token, private
key, password, prompt text, resource URI, or provider response body.

## Provider rules

1. Construct peer profiles and dispatches through `flowweft-agent-api`; never reconstruct their
   tenant, principal, authorization, network, credential, deadline, budget or idempotency fields.
2. Return only descriptors previously reviewed in the adapter/host registry. The opaque resource or
   prompt ID resolves inside that registry; caller data cannot select a network endpoint.
3. `capabilities()` is read-only. It must not issue an extra network request that is absent from the
   supplied `AgentInteroperabilityDispatchEvidence`.
4. `inspect()` is side-effect free. It must not dispatch, retry, cancel or reconcile an operation.
5. Normalize exceptional stages with the existing Agent provider-failure boundary. Do not copy raw
   exception messages, headers, payloads, endpoints or credential material into findings.
6. A stale or changed capability/catalog digest fails closed. Do not silently reuse an older catalog.

## Intentional non-goals and remaining adapter work

The existing `AgentRemoteOperationKind` does not yet contain MCP resource-read or prompt-get
operations. This module deliberately does not disguise them as tool calls or invent a parallel
dispatch model. It supplies reviewed catalog metadata and capability/Doctor ports only. Executing
resource or prompt operations requires a separately reviewed additive change to the canonical Agent
remote API, strict codec/runtime support, authorization and compatibility tests.

A2A transport, Agent Card descriptor binding, messages, cancellation, asynchronous tasks, unknown
outcomes and reconciliation remain exclusively in `flowweft-agent-api` and its HTTP/runtime
adapters. Concrete SDKs, HTTP clients, OAuth exchanges, DNS resolution and secret brokers belong in
adapter modules, never here.

## Change-control summary

- Existing extension point insufficiency: the canonical API has no first-class MCP resource/prompt
  catalog digest.
- Compatibility impact: additive module and additive `AgentCapabilityId` values only; no existing ABI
  or database migration changes.
- Migration: hosts opt in by adding the new capability IDs and an approved catalog provider. Existing
  MCP tools and A2A behavior are unchanged.
- Verification: Kotlin invariants, Java consumer compilation, defensive redaction, digest drift,
  exact dispatch/result matching and Doctor result-state tests.
