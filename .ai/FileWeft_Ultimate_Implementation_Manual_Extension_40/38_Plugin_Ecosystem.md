
# Plugin Ecosystem

> **CURRENT NOTICE — HISTORICAL BODY BELOW.** `0.0.2` and `0.0.3` did not expose
> Agent plugins. The old indefinite deferral is now superseded by root
> `AGENTS.md` and ADR 0001: FlowWeft 1.0 includes a redesigned, additive Agent
> product. Do not repurpose the retained `fileweft-agent` ABI; apply the current
> plugin and Agent contracts instead of implementing this historical sketch.


Plugin can add:


- new storage
- new connector
- new workflow
- new agent (historical/superseded; compatibility SPI only)


Plugin cannot modify core.


Plugin discovery:

Spring Bean

or

Java ServiceLoader
