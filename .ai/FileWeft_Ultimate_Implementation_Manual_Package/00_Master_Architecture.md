
# FileWeft Ultimate Implementation Manual

This package is the implementation source of truth.

FileWeft is a Kotlin/JVM enterprise file intelligence infrastructure.

Core principles:
- Stable Core
- SPI first
- Adapter isolation
- Event driven
- Tenant aware
- Production diagnosable

Dependency direction:

starter -> application -> domain -> core

adapter -> spi

Never:
core -> Spring
domain -> vendor SDK
spi -> implementation
