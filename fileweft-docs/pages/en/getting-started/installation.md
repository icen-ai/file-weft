---
route: "getting-started/installation"
group: "getting-started"
order: 2
locale: "en"
nav: "Installation"
title: "Install the 0.0.1 line"
lead: "Add FileWeft to your build, align Spring Boot generations, and verify the dependency tree."
format: "markdown"
---

## What this page covers

This page shows the exact Maven coordinates for the stable `0.0.1` release, the JDK and Spring Boot constraints, and a quick command to confirm that the right artifacts landed in your classpath.

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
    implementation("ai.icen:fileweft-spring-boot3-starter:0.0.1")
    implementation("ai.icen:fileweft-web-spring-boot3-starter:0.0.1")

    // If you only need the SPI contracts
    // implementation("ai.icen:fileweft-spi:0.0.1")
}
```

### Maven

```xml
<dependencies>
    <dependency>
        <groupId>ai.icen</groupId>
        <artifactId>fileweft-spring-boot3-starter</artifactId>
        <version>0.0.1</version>
    </dependency>
    <dependency>
        <groupId>ai.icen</groupId>
        <artifactId>fileweft-web-spring-boot3-starter</artifactId>
        <version>0.0.1</version>
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
| Spring Boot 2 | `ai.icen:fileweft-spring-boot2-starter:0.0.1` | `ai.icen:fileweft-web-spring-boot2-starter:0.0.1` | Java 8 |
| Spring Boot 3 | `ai.icen:fileweft-spring-boot3-starter:0.0.1` | `ai.icen:fileweft-web-spring-boot3-starter:0.0.1` | Java 17 |

> [!NOTE]
> The runtime starter bundles persistence, workers, application services, and observability adapters. The web starter adds the formal `/fileweft/v1` HTTP surface. Add both if you want a runnable REST API.

## Verify the dependency

Run Gradle's dependency insight to confirm that `0.0.1` is selected and that no withdrawn `com.fileweft` artifacts leaked in:

```bash
# Linux / macOS
./gradlew dependencyInsight --dependency fileweft-spi --configuration runtimeClasspath

# Windows PowerShell
.\gradlew.bat dependencyInsight --dependency fileweft-spi --configuration runtimeClasspath
```

You should see a tree rooted at `ai.icen:fileweft-spi:0.0.1`. If `com.fileweft` appears anywhere, remove that dependency.

## FAQ

**Q: Can I use the `0.0.2-SNAPSHOT` line in production?**

No. `0.0.1` is the current stable release. Snapshot builds contain unreleased APIs and behaviors; do not rely on them in production documentation.

**Q: Do I need both the runtime starter and the web starter?**

If you only call FileWeft from Kotlin/Java code inside your Spring application, the runtime starter is enough. If you want the `/fileweft/v1` REST endpoints, add the matching web starter.

**Q: Can I depend on `fileweft-persistence` directly instead of the runtime starter?**

Yes. The runtime starter is a convenience bundle. For finer control, you can depend on `fileweft-persistence` plus the other modules your host needs.

## Next steps

- [Wire a trustworthy host](first-integration.md)
- [Run the 5-minute quickstart](quickstart.md)
