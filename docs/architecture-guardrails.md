# 架构防回归门禁

`verifyFileWeftArchitecture` 扫描生产 Kotlin 源码的 `import` 及少量关键语法，以最快、最稳定的方式阻止不可接受的依赖泄漏；它与 `build-logic` TestKit 回归一起纳入 `fastCheck`，根 `check` 仍是兼容聚合入口但不是日常开发默认命令。门禁采用“允许命名空间白名单”：每层只能导入本层允许的 FileWeft 层、JDK（`java` / `javax`）和 Kotlin 类型；任何未知第三方或上层模块命名空间都会失败。

- Core 只能依赖 Core；不得依赖上层 FileWeft 模块、Spring、JDBC、Flyway 或厂商 SDK。
- SPI 只能依赖 Core 与 SPI；不得依赖 Domain、Application、Persistence、Adapter、Starter、数据库或厂商 SDK。
- Domain 只能依赖 Core 与 Domain；不得反向依赖 SPI、Application、Persistence、Adapter、Starter、数据库或厂商 SDK。
- Metadata API 只能依赖自身命名空间、JDK 与 Kotlin；不得依赖 Spring、JDBC、HTTP、ORM、Application 或其他 FileWeft 运行时模块。
- Metadata Runtime 只能依赖 Metadata API/Runtime、JDK、Kotlin，以及仅用于线性时间 `format` 校验的 `com.google.re2j`；不得反向依赖 Application、Web、Persistence、Starter 或其他外部框架。该例外不扩展到 Metadata API 或其他模块。
- Application 只能面向 Application、Core、Domain、SPI 与 Metadata API；不得引用 Metadata Runtime、Persistence、Runtime、Adapter、Agent、Starter、数据库或厂商 SDK。
- Web API 保持纯公共契约；Web Runtime 可以依赖 Web API、Application、Core、Domain 与 Metadata API，但不能引入 Spring、Servlet、JDBC 或持久化实现。

即使 `javax` 命名空间被允许以保持 JDK 8 兼容，`java.sql`、`javax.sql`、`javax.persistence` 与 `jakarta.persistence` 仍被显式拒绝。

Core、SPI、Metadata API/Runtime 与 Web API/Runtime 还禁止 `suspend fun`、`value class`、`sealed interface` 和 `data object`。这比“公共 API 不应泄漏 Kotlin-only 类型”的最低要求更严格，避免基础契约出现难以从 Java 使用的类型。

该门禁补充 Gradle 的模块依赖声明：后者能阻止常规项目依赖，前者还能在源码层发现手工加入的基础设施导入。它不扫描测试源码，测试可以按需要使用对应的测试库；也不替代对领域语义、事务边界和多租户查询条件的代码评审。

需要扩展架构时，必须先修改设计文档并说明兼容性、迁移策略和测试计划，再有意识地调整本门禁；不能为了让构建通过而放宽基础层规则。
