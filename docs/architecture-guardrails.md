# 架构防回归门禁

`./gradlew check` 会自动执行 `verifyFileWeftArchitecture`，并运行 `build-logic` 中的 TestKit 回归测试。门禁只扫描生产 Kotlin 源码的 `import`，以最快、最稳定的方式阻止以下不可接受的依赖泄漏。门禁采用“允许命名空间白名单”：每层只能导入本层允许的 FileWeft 层、JDK（`java` / `javax`）和 Kotlin 类型；任何未知第三方或上层模块命名空间都会失败。

- Core 只能依赖 Core；不得依赖上层 FileWeft 模块、Spring、JDBC、Flyway 或厂商 SDK。
- SPI 只能依赖 Core 与 SPI；不得依赖 Domain、Application、Persistence、Adapter、Starter、数据库或厂商 SDK。
- Domain 只能依赖 Core 与 Domain；不得反向依赖 SPI、Application、Persistence、Adapter、Starter、数据库或厂商 SDK。
- Application 只能面向 Application、Core、Domain 与 SPI；不得引用 Persistence、Runtime、Adapter、Agent、Starter、数据库或厂商 SDK。

即使 `javax` 命名空间被允许以保持 JDK 8 兼容，`java.sql`、`javax.sql`、`javax.persistence` 与 `jakarta.persistence` 仍被显式拒绝。

该门禁补充 Gradle 的模块依赖声明：后者能阻止常规项目依赖，前者还能在源码层发现手工加入的基础设施导入。它不扫描测试源码，测试可以按需要使用对应的测试库；也不替代对领域语义、事务边界和多租户查询条件的代码评审。

需要扩展架构时，必须先修改设计文档并说明兼容性、迁移策略和测试计划，再有意识地调整本门禁；不能为了让构建通过而放宽基础层规则。
