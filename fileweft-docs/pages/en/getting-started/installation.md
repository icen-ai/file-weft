---
route: "getting-started/installation"
group: "getting-started"
order: 2
locale: "en"
nav: "Installation"
title: "Install the 0.0.3 line"
lead: "Add FileWeft to your build, align Spring Boot generations, and verify the dependency tree."
format: "markdown"
---

## What this page covers

This page shows the exact Maven coordinates for stable `v0.0.3`, the JDK and Spring Boot constraints, and a quick command to confirm that the right artifacts landed in your classpath. Commit `dbf2a50fbca41e2ac5b5cf18bb44f9287c153637` completed all 12/12 CNB release pipelines in build `cnb-cl8-1jtgih45j`, followed by anonymous readback of all 19 coordinates, so these stable coordinates are ready to use.

## Before you begin

| Requirement | Detail |
| --- | --- |
| Build JDK | JDK 17 or newer; the verified build environment is JDK 21. |
| Baseline bytecode | Core modules publish Java 8-compatible bytecode. |
| Spring Boot 3 starter | Requires Java 17 at runtime. |
| Spring Boot 2 starter | Retains the Java 8 baseline. |

> [!WARNING]
> Do not mix Boot 2 and Boot 3 FileWeft starters in the same application. The Web API contract is identical, but the underlying Spring dependencies are not.

## Maven coordinates

FileWeft is published under Maven group `ai.icen` with JVM package `ai.icen.fw`. The old `com.fileweft:*:0.0.1` trial coordinates were withdrawn and are not supported.

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://maven.cnb.cool/china.ai/maven/-/packages/") }
}

dependencies {
    // Spring Boot 3 host with REST API
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("ai.icen:fileweft-spring-boot3-starter:0.0.3")
    implementation("ai.icen:fileweft-web-spring-boot3-starter:0.0.3")

    // If you only need the SPI contracts
    // implementation("ai.icen:fileweft-spi:0.0.3")
}
```

### Maven

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
        <groupId>ai.icen</groupId>
        <artifactId>fileweft-spring-boot3-starter</artifactId>
        <version>0.0.3</version>
    </dependency>
    <dependency>
        <groupId>ai.icen</groupId>
        <artifactId>fileweft-web-spring-boot3-starter</artifactId>
        <version>0.0.3</version>
    </dependency>
</dependencies>

<repositories>
    <repository>
        <id>maven-central</id>
        <url>https://repo.maven.apache.org/maven2</url>
    </repository>
    <repository>
        <id>cnb</id>
        <url>https://maven.cnb.cool/china.ai/maven/-/packages/</url>
    </repository>
</repositories>
```

## Spring Boot generation alignment

| Boot generation | Runtime starter | Web starter | JVM baseline |
| --- | --- | --- | --- |
| Spring Boot 2 | `ai.icen:fileweft-spring-boot2-starter:0.0.3` | `ai.icen:fileweft-web-spring-boot2-starter:0.0.3` | Java 8 |
| Spring Boot 3 | `ai.icen:fileweft-spring-boot3-starter:0.0.3` | `ai.icen:fileweft-web-spring-boot3-starter:0.0.3` | Java 17 |

> [!NOTE]
> The runtime starter bundles persistence, workers, application services, and observability adapters. The web starter adds the formal `/fileweft/v1` HTTP surface. Add both if you want a runnable REST API. `spring-boot-starter-jdbc` is a host dependency whose version is managed by the host's Spring Boot Gradle plugin, parent, or BOM; FileWeft does not transitively select HikariCP or another pool.

### Align the Kotlin runtime on Boot 2

Boot 2.7's BOM manages Kotlin at 1.6.21, below FileWeft's 2.1.21; Java-only hosts must align it too because they still load FileWeft's Kotlin bytecode. Set `extra["kotlin.version"] = "2.1.21"` with Spring Dependency Management, set `<kotlin.version>2.1.21</kotlin.version>` with Maven, or add `org.jetbrains.kotlin:kotlin-bom:2.1.21` (or an equivalent explicit resolution rule) with native Gradle platforms. An ordinary Kotlin BOM cannot override a Boot 2 `enforcedPlatform`; use `dependencyInsight` to confirm the final `kotlin-stdlib` version is 2.1.21.

## Verify the dependency

After remote publication is verified, run Gradle's dependency insight to confirm that `0.0.3` is selected, the host JDBC starter is present at runtime, and no withdrawn `com.fileweft` artifacts leaked in:

```bash
# Linux / macOS
./gradlew dependencyInsight --dependency fileweft-spi --configuration runtimeClasspath
./gradlew dependencyInsight --dependency spring-boot-starter-jdbc --configuration runtimeClasspath

# Windows PowerShell
.\gradlew.bat dependencyInsight --dependency fileweft-spi --configuration runtimeClasspath
.\gradlew.bat dependencyInsight --dependency spring-boot-starter-jdbc --configuration runtimeClasspath
```

You should see both `ai.icen:fileweft-spi:0.0.3` and a host-managed `org.springframework.boot:spring-boot-starter-jdbc`. If `com.fileweft` appears anywhere, remove that dependency.

## FAQ

**Q: When may I use `0.0.3` in production?**

Now. Stable `v0.0.3` is fixed at `dbf2a50fbca41e2ac5b5cf18bb44f9287c153637`; CNB build `cnb-cl8-1jtgih45j` completed 12/12 pipelines and anonymous readback verified all 19 coordinates. Continue to follow the V029 upgrade boundary below rather than treating artifact availability as permission for an unsafe rolling migration.

**Q: Do I need both the runtime starter and the web starter?**

If you only call FileWeft from Kotlin/Java code inside your Spring application, the runtime starter is enough. If you want the `/fileweft/v1` REST endpoints, add the matching web starter.

**Q: Can I depend on `fileweft-persistence` directly instead of the runtime starter?**

Yes. The runtime starter is a convenience bundle. For finer control, you can depend on `fileweft-persistence` plus the other modules your host needs.

## Next steps

- [Wire a trustworthy host](first-integration.md)
- [Run the 5-minute quickstart](quickstart.md)
