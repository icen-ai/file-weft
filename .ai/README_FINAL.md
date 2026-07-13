# FileWeft Ultimate Implementation Manual FINAL Package

## Superseding decision: Agent product capability is deferred

This decision overrides every older Agent phase, architecture sketch, plugin
example, and acceptance statement in this package when they conflict with it.

- `0.0.2` does not provide Agent product capability.
- Agent redesign is deferred indefinitely. It may be reassessed only after
  `1.0.0` has been released, and that reassessment is not a promise for `1.x`
  or any other version.
- `fileweft-agent`, Agent SPI/public ABI, and Agent-related V012/V026 schema are
  retained only for compatibility. Default runtime, Doctor/plugin inventory,
  public HTTP, and Dev surfaces must not expose them.
- Historical Agent sections remain in the manuals for traceability, but they
  are superseded and must not be treated as implementation work.

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
