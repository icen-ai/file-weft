
# FileWeft Ultimate Implementation Manual

This package is the single source of truth.

Purpose:
Implement FileWeft as a Kotlin/JVM enterprise file infrastructure.

Rules:
- Do not redesign architecture.
- Extend through SPI.
- Keep core stable.
- Keep public API Java friendly.

Architecture:

starter
 -> application
 -> domain
 -> core

adapter
 -> spi

