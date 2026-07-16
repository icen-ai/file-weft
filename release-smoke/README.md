# FlowWeft 发布消费者门禁

`releaseSmoke` 是发布制品门禁的一部分，不是日常开发测试入口。它从待发布的 Maven
仓库冷解析制品，并用四条彼此独立的消费者路径避免元数据格式相互掩盖：

公开制品集合只从 `../gradle/publication-inventory.tsv` 读取；不要在本目录复制 artifact
列表。清单中的 `artifactKind` 决定当前 JAR 消费库存，`lineage` 决定新的 FlowWeft 产品
面，`jvmBaseline` 则用于校验 Gradle 变体的目标 JVM 属性。

- 外层 Gradle build 在 `settings.gradle.kts` 中只启用 `mavenPom()` 与 `artifact()`，
  编译独立 Java 8、Kotlin、Spring Boot 2 和 Spring Boot 3 消费者，并核对迁移 CLI
  的 PostgreSQL/MySQL/protobuf 运行图及可选 OCI SDK 排除。
- `gradle-module-consumer/` 使用 Gradle 默认 `metadataSources`，必须读到 `.module`
  中的 `apiElements`、`runtimeElements`、`sourcesElements`、Java 8 属性、文件与内部依赖。
  待验证的 `flowweft-*` 列表由外层发布库存传入，不能在这里另建一个固定总库存；
  它还会证明宿主误用旧版 enforced Boot BOM 时与安全版本严格约束冲突，而不是静默降级。
- `maven-consumer/` 使用真实 Maven CLI 和隔离的本地仓库，仅按 POM 编译旧 ABI 与
  新 1.0 API。CI 必须预装固定的 Maven 3.9.10 并把 `mvn` 放入 `PATH`；门禁不会下载
  Maven 运行时，找不到 Maven 时会失败关闭。
- `maven-boot3-consumer/` 在 JDK 17 上仅按 POM 编译两类 Boot 3 Starter，同时记录
  runtime dependency tree，强制 Tomcat 三件套和 Logback 两件套解析到仓库声明的安全
  版本，并证明 `tomcat-annotations-api` 没有因直接安全依赖而被意外带回。

新增公开模块时，应先更新根发布库存；如果它属于新的 `flowweft-*` 产品面，Gradle
metadata lane 会自动纳入其二进制与 sources 变体。稳定代表类型确定后，还应在 Java、
Kotlin 或 Maven consumer 中增加最小的类型引用，使空 JAR、错 JAR 或缺失 ABI 无法通过。
