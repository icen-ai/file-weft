
# AI Agent

> **SUPERSEDED — DO NOT IMPLEMENT AS CURRENT PRODUCT SCOPE.** `0.0.2` does not
> provide Agent capability. Redesign is deferred indefinitely and may only be
> reassessed after `1.0.0` has been released; that is not a promise for `1.x`
> or any other version. Existing `fileweft-agent`, Agent SPI/public ABI, and
> V012/V026 schema remain compatibility-only. The default runtime, Doctor and
> plugin inventories, public HTTP API, and Dev application must not expose
> Agent.

## Historical design (superseded)

Agent is optional.

Flow:

Event
 -> AgentTask
 -> AgentResult
 -> Suggestion
 -> Confirm


Agents:

Metadata
Duplicate
Classification
Security


Agent cannot directly mutate domain.
