
# FileWeft Master Architecture

> **Superseding Agent boundary:** the Agent layer listed below is retained as
> historical architecture and compatibility surface only. `0.0.2` does not
> expose it by default. Redesign may be reassessed only after `1.0.0` is
> released, with no promised delivery version.

FileWeft is a Kotlin/JVM enterprise file intelligence infrastructure.

Primary goal:
A customer imports a starter, implements a few SPI interfaces, and obtains a complete file lifecycle platform.

Architecture layers:

core
spi
domain
application
persistence
runtime
adapter
starter
agent (historical; compatibility-only artifact/SPI)

Dependency direction is immutable:

starter -> application -> domain -> core
adapter -> spi

Never reverse dependencies.
