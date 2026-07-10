
# FileWeft Master Architecture

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
agent

Dependency direction is immutable:

starter -> application -> domain -> core
adapter -> spi

Never reverse dependencies.
