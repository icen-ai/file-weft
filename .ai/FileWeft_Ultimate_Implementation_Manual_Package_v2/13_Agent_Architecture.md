
# Agent Architecture

> **SUPERSEDED — HISTORICAL DESIGN ONLY.** `0.0.2` does not provide Agent
> product capability. Redesign is deferred indefinitely and may be reassessed
> only after `1.0.0` is released; this does not promise a `1.x` or other
> delivery. Existing artifact, SPI/public ABI, and V012/V026 schema are retained
> only for compatibility, and default runtime/Doctor/plugin/HTTP/Dev surfaces
> must not expose Agent.

## Historical design (superseded)

Agent is optional.

Agent never directly modifies domain.

Flow:

Event

Agent Task

Agent Result

Suggestion

Confirm


Agents:

Metadata

Duplicate

Classification

Security
