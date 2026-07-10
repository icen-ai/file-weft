
# Runtime Compatibility

Supported:

JDK 8 - JDK 25

Kotlin implementation.

Public APIs must remain Java compatible.

Forbidden public API:

- suspend
- Flow
- value class
- sealed interface
- data object

Spring Boot:

Boot2 starter:
JDK8+

Boot3 starter:
JDK17+

Core:
pure JVM.
