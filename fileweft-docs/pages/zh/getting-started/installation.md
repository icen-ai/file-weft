---
route: "getting-started/installation"
group: "getting-started"
order: 2
locale: "zh"
nav: "安装"
title: "安装 0.0.1 正式版"
lead: "将 FileWeft 加入构建，对齐 Spring Boot 代际，并验证依赖树。"
format: "markdown"
---

## 这页解决什么问题？

这页给出稳定版 `0.0.1` 的 Maven 坐标、JDK 与 Spring Boot 约束，以及一个快速命令来确认正确的 artifacts 已进入你的 classpath。

## 开始之前

| 要求 | 说明 |
| --- | --- |
| 构建 JDK | JDK 17+，当前验证环境为 JDK 21。 |
| 基线字节码 | 基础模块发布 Java 8 兼容字节码。 |
| Spring Boot 3 Starter | 运行时需 Java 17。 |
| Spring Boot 2 Starter | 保持 Java 8 基线。 |

> [!WARNING]
> 不要在同一应用中混用 Boot 2 与 Boot 3 的 FileWeft Starter。Web API 契约一致，但底层 Spring 依赖不同。

## Maven 坐标

FileWeft 的 Maven group 为 `ai.icen`，JVM 包名为 `ai.icen.fw`。早期 `com.fileweft:*:0.0.1` 试推坐标已撤回，不再受支持。

### Gradle（Kotlin DSL）

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://maven.cnb.cool/china.ai/maven/-/packages/") }
}

dependencies {
    // 带 REST API 的 Spring Boot 3 宿主
    implementation("ai.icen:fileweft-spring-boot3-starter:0.0.1")
    implementation("ai.icen:fileweft-web-spring-boot3-starter:0.0.1")

    // 若只需要 SPI 契约
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

## Spring Boot 代际对齐

| Boot 代际 | Runtime starter | Web starter | JVM 基线 |
| --- | --- | --- | --- |
| Spring Boot 2 | `ai.icen:fileweft-spring-boot2-starter:0.0.1` | `ai.icen:fileweft-web-spring-boot2-starter:0.0.1` | Java 8 |
| Spring Boot 3 | `ai.icen:fileweft-spring-boot3-starter:0.0.1` | `ai.icen:fileweft-web-spring-boot3-starter:0.0.1` | Java 17 |

> [!NOTE]
> Runtime starter 是便利包，包含持久化、Worker、应用服务与观测适配器。Web starter 额外暴露正式的 `/fileweft/v1` HTTP 接口。若需要可运行的 REST API，两者都加。

## 验证依赖

运行 Gradle 依赖洞察，确认选中的是 `0.0.1`，且没有 withdrawn 的 `com.fileweft` artifacts 混入：

```bash
# Linux / macOS
./gradlew dependencyInsight --dependency fileweft-spi --configuration runtimeClasspath

# Windows PowerShell
.\gradlew.bat dependencyInsight --dependency fileweft-spi --configuration runtimeClasspath
```

你应看到以 `ai.icen:fileweft-spi:0.0.1` 为根的树。如果任何位置出现 `com.fileweft`，请移除该依赖。

## 常见问题

**Q: 生产环境可以用 `0.0.2-SNAPSHOT` 吗？**

不可以。`0.0.1` 是当前稳定版。Snapshot 构建包含未发布的 API 和行为，不要在生产文档中依赖它们。

**Q: 是否必须同时引入 runtime starter 和 web starter？**

如果只在 Spring 应用内部通过 Kotlin/Java 调用 FileWeft，runtime starter 足够。若需要 `/fileweft/v1` REST 端点，再加对应 web starter。

**Q: 可以不引 runtime starter、直接依赖 `fileweft-persistence` 吗？**

可以。Runtime starter 是便利包。如需更细粒度控制，可单独依赖 `fileweft-persistence` 及其他所需模块。

## 下一步

- [装配可信宿主](first-integration.md)
- [5 分钟快速开始](quickstart.md)
