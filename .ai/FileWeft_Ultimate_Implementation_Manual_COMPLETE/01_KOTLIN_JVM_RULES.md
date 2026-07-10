
# Kotlin JVM Rules

Targets:
JDK8-JDK25

Public API:
Allowed:
- interface
- class
- DTO

Forbidden:
- suspend
- Flow
- value class
- sealed interface
- data object

Core modules are pure Kotlin/JVM.
Spring integration belongs to starter.
