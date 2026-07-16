
# Agent Architecture

> **CURRENT NOTICE — HISTORICAL DESIGN ONLY.** `0.0.2` and `0.0.3` did not
> provide Agent product capability. The old indefinite deferral is superseded
> by root `AGENTS.md` and ADR 0001: FlowWeft 1.0 includes a redesigned,
> additive Agent product. Existing artifact, SPI/public ABI, and V012/V026
> schema remain compatibility-only; do not repurpose them or implement the
> historical model below as the current architecture.

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
