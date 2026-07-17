# FileWeft Ultimate Implementation Manual FINAL Package

## Superseding decision: 1.0 includes the redesigned Agent

The former indefinite deferral remains a historical fact for `0.0.2` and
`0.0.3`, but it no longer controls development after `0.0.3`. The approved 1.0
decision is `docs/decisions/0001-flowweft-1.0-product-scope.md`, and the
acceptance source of truth is `docs/flowweft-1.0-delivery-ledger.md`.

- `fileweft-agent`, the `ai.icen.fw.spi.ai` ABI, and Agent-related V012/V026
  schema remain compatibility-only and must not be repurposed.
- The redesigned Agent, retrieval contracts, runtime, persistence, HTTP surface,
  Doctor coverage, evaluation, and console are additive 1.0 work.
- Only safe filename matching is built in. Model, extraction, full-text,
  vector, hybrid, reranking, tool, MCP, and A2A capabilities are explicit SPIs.
- RustFS, Dify knowledge base, and Alibaba Cloud OSS are the only maintained
  reference integrations in the 1.0 scope. Historical ESE/AppBuilder examples
  do not create a delivery obligation.
- Historical Agent sections in this package remain for traceability. Treat them
  as design input only where they agree with the current ADR and repository
  architecture rules.

This is the final consolidated package.

It contains all previously produced architecture, implementation,
database, runtime, source blueprint, testing, security, deployment,
and AI execution documents.

Implementation principles:

- Kotlin/JVM enterprise infrastructure
- JDK8-JDK25 compatibility
- Spring Boot 2/3 compatibility
- SPI first architecture
- Core stability
- Adapter isolation
- Event driven workflow
- Multi tenant design
- Production diagnosis

Use this package as the single source of truth for implementation.
Do not redesign existing boundaries.
