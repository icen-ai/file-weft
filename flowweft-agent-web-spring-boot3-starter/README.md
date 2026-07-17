# FlowWeft Agent Web Spring Boot 3 Starter

Servlet/Jackson HTTP adapter for all 25 routes in `AgentWebRoute`. It targets Java 17 and the
`jakarta.servlet` Spring Boot 3 stack. The starter does not authenticate users, load providers,
access a repository, enable CORS, or alter the host's CSRF/security filter chain.

## Host wiring required

The host must register exactly one `AgentWebTrustedContextProvider`. Its request-scoped context
must be built from verified host authentication and current authorization evidence. Tenant and
principal values are never read from HTTP headers, paths, query parameters, or JSON. Register the
application ports needed by the deployment:

- `AgentConversationWebApplicationPort`
- `AgentRunWebApplicationPort`
- `AgentToolConfirmationWebApplicationPort`
- `AgentConfigurationWebApplicationPort`
- `AgentEvaluationWebApplicationPort`

A missing feature port returns `503 CAPABILITY_UNSUPPORTED`; no adapter silently substitutes a
less secure implementation. Set `flowweft.agent.web.enabled=false` to disable route registration.

Every mutation requires both `Idempotency-Key` and a strong `If-Match: "fw-agent-N"`. Persist the
opaque cursor returned by list responses. For run events, JSON polling is the default; request
`Accept: text/event-stream` for a bounded SSE batch and reconnect with the opaque cursor emitted by
the `flowweft.cursor` control event. Event sequence IDs are ordering hints, not cursor substitutes.

Provider configuration accepts only administrator-owned connection/credential references. It has
no endpoint, URL, password, token, or secret field. The starter returns code-only errors and never
serializes provider exceptions.
