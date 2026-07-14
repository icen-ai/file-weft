---
route: "getting-started/installation"
group: "getting-started"
order: 2
locale: "zh"
nav: "安装"
title: "安装 0.0.3 版本线"
lead: "将 FileWeft 加入构建，对齐 Spring Boot 代际，并验证依赖树。"
format: "markdown"
---

## 这页讲什么

这页给出 `v0.0.3` 发布合同的 Maven 坐标、JDK 与 Spring Boot 约束，以及一个快速命令来确认正确的 artifacts 已进入你的 classpath。本文不是发布证据；只有受发布门禁约束的 `v0.0.3` 标签匹配受保护远端 `main` HEAD、精确提交的全部必需门禁成功，且匿名消费者以全新隔离缓存回读全部 19 个坐标及 Boot 2、Boot 3、纯 SPI 消费者后，才能使用这些稳定坐标。

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

FileWeft 的 Maven group 为 `ai.icen`，JVM 包名为 `ai.icen.fw`。早期 `com.fileweft:*:0.0.1` 试用坐标已撤回，不再受支持。

### Gradle（Kotlin DSL）

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://maven.cnb.cool/china.ai/maven/-/packages/") }
}

dependencies {
    // 带 REST API 的 Spring Boot 3 宿主
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("ai.icen:fileweft-spring-boot3-starter:0.0.3")
    implementation("ai.icen:fileweft-web-spring-boot3-starter:0.0.3")

    // 若只需要 SPI 契约
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

## Spring Boot 代际对齐

| Boot 代际 | Runtime starter | Web starter | JVM 基线 |
| --- | --- | --- | --- |
| Spring Boot 2 | `ai.icen:fileweft-spring-boot2-starter:0.0.3` | `ai.icen:fileweft-web-spring-boot2-starter:0.0.3` | Java 8 |
| Spring Boot 3 | `ai.icen:fileweft-spring-boot3-starter:0.0.3` | `ai.icen:fileweft-web-spring-boot3-starter:0.0.3` | Java 17 |

> [!NOTE]
> Runtime starter 是便利包，包含持久化、Worker、应用服务与观测适配器。Web starter 额外暴露正式的 `/fileweft/v1` HTTP 接口。若需要可运行的 REST API，两者都加。`spring-boot-starter-jdbc` 属于宿主依赖，其版本应由宿主的 Spring Boot Gradle 插件、Parent 或 BOM 管理；FileWeft 不传递选择 HikariCP 或其他连接池。

### Boot 2 的 Kotlin 运行时对齐

Boot 2.7 BOM 默认把 Kotlin 管理为 1.6.21，低于 FileWeft 使用的 2.1.21；纯 Java 宿主运行 FileWeft 字节码时同样需要对齐。使用 Spring Dependency Management 时设置 `extra["kotlin.version"] = "2.1.21"`，Maven 设置 `<kotlin.version>2.1.21</kotlin.version>`；使用原生 Gradle platform 时同时导入 `org.jetbrains.kotlin:kotlin-bom:2.1.21` 或采用等价的显式解析规则。普通 Kotlin BOM 不能覆盖 Boot 2 `enforcedPlatform`；请用 `dependencyInsight` 确认 `kotlin-stdlib` 最终为 2.1.21。

## 验证依赖

远端发布完成验证后，运行 Gradle 依赖洞察，确认选中的是 `0.0.3`、宿主 JDBC Starter 已进入运行时 classpath，且没有已撤回的 `com.fileweft` artifacts 混入：

```bash
# Linux / macOS
./gradlew dependencyInsight --dependency fileweft-spi --configuration runtimeClasspath
./gradlew dependencyInsight --dependency spring-boot-starter-jdbc --configuration runtimeClasspath

# Windows PowerShell
.\gradlew.bat dependencyInsight --dependency fileweft-spi --configuration runtimeClasspath
.\gradlew.bat dependencyInsight --dependency spring-boot-starter-jdbc --configuration runtimeClasspath
```

应当同时看到 `ai.icen:fileweft-spi:0.0.3` 与宿主管理版本的 `org.springframework.boot:spring-boot-starter-jdbc`。如果任何位置出现 `com.fileweft`，请移除该依赖。

## 常见问题

**Q: 什么时候可以在生产环境使用 `0.0.3`？**

只有受发布门禁约束的 `v0.0.3` 标签匹配受保护远端 `main` HEAD、精确提交的全部必需门禁成功，且匿名消费者以全新隔离缓存回读全部 19 个坐标及 Boot 2、Boot 3、纯 SPI 消费者后才可以。源码检出、本文、本地 Maven 发布、标签名称、部分绿灯或 SNAPSHOT 都不是等价证据。

**Q: 是否必须同时引入 runtime starter 和 web starter？**

如果只在 Spring 应用内部通过 Kotlin/Java 调用 FileWeft，runtime starter 足够。若需要 `/fileweft/v1` REST 端点，再加对应 web starter。

**Q: 可以不引 runtime starter、直接依赖 `fileweft-persistence` 吗？**

可以。Runtime starter 是便利包。如需更细粒度控制，可单独依赖 `fileweft-persistence` 及其他所需模块。

## 下一步

- [接入可信宿主](first-integration.md)
- [5 分钟快速开始](quickstart.md)
