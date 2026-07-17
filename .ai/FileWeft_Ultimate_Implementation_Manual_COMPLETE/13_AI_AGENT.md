
# AI Agent

> **CURRENT NOTICE — HISTORICAL DESIGN BELOW.** `0.0.2` and `0.0.3` did not
> provide Agent capability. The old indefinite deferral is superseded by root
> `AGENTS.md` and ADR 0001: FlowWeft 1.0 includes a redesigned, additive Agent
> product. Existing `fileweft-agent`, Agent SPI/public ABI, and V012/V026 schema
> remain compatibility-only and must not be repurposed. Do not implement the
> historical model below as the current architecture.

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
