
# Kotlin JVM Compatibility

Targets:
- JDK 8
- JDK 11
- JDK 17
- JDK 21
- JDK 25

Public API rules:
Forbidden:
- suspend
- Flow
- value class
- sealed interface
- data object

Reason:
SPI compatibility is more important than Kotlin syntax convenience.

Core modules:
jvmTarget 8

Spring Boot 2 starter:
JDK8+

Spring Boot 3 starter:
JDK17+
