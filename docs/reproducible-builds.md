# 可复现构建与依赖验证

FileWeft 对所有常规模块和 included `build-logic` 启用了 Gradle dependency locking。每个 `gradle.lockfile` 与根目录的 `settings-gradle.lockfile` 都必须提交；普通构建会使用这些状态，避免不同机器在相同源码下解析到漂移的传递依赖。

`gradle/verification-metadata.xml` 保存每个已解析制品的 SHA-256。Gradle 在该文件存在时会验证依赖制品；校验不一致的下载会失败，而不是继续构建。

日常验证：

```powershell
.\gradlew.bat check --no-daemon
```

发版前还应执行完整的 JVM 运行时矩阵：

```powershell
.\gradlew.bat compatibilityCheck --no-daemon
```

该门禁使用 Gradle 工具链，而不是依赖执行 Gradle 的 JVM：Java 8 基线模块在 Java 8、11、21、25 上运行测试；Java 17 模块在 Java 17、21、25 上运行测试。缺失的 JDK 由已配置的 Foojay 工具链解析器自动取得；首次运行的下载耗时应由发布流水线预留。

有意识地升级依赖后，先审阅版本目录和构建脚本，再重新生成两类状态：

```powershell
.\gradlew.bat check --write-locks --no-daemon
.\gradlew.bat check --write-verification-metadata sha256 --no-daemon
```

最后再次运行不带写入参数的 `check`，并在提交前审阅所有 `*.lockfile` 和 `gradle/verification-metadata.xml` 的增量。不要手工修改这些 Gradle 生成文件；若出现未知组件、意外版本或校验和变化，应先确认其来源和升级理由。

## 发布 SBOM

发版流水线应在锁定依赖、验证 SHA-256 后执行：

```powershell
.\gradlew.bat verifySbom --no-daemon
```

CycloneDX Direct BOM 只解析 17 个正式发布模块各自独立的 `runtimeClasspath`，关闭 Gradle build environment，并禁用未发布的 `fileweft-dev`。不能把 17 个模块合并到一个 configuration 后再解析，否则 Gradle 的版本冲突消解会错误丢弃 Boot 2/3 等替代型模块各自合法的运行时版本。

原始聚合结果位于 `build/reports/cyclonedx/`；发布任务会删除非发布内部模块以及只由 Dev/Test/Build 节点可达的第三方孤儿，补齐根到 17 个模块的发布关系，移除时间戳和随机序列号，并将确定性 JSON/XML 输出到 `build/reports/cyclonedx-release/`。`verifySbom` 校验正式模块集合、版本、Apache-2.0 许可证、坐标和依赖图双格式一致性。Mockito、AssertJ、Kotlin compiler 与 Boot starter-test 不属于发布闭包；`fileweft-testkit` 公共契约需要的 JUnit 5.11.4 则是合法运行时依赖，不得按名称误删。

发布系统只归档 `cyclonedx-release/bom.json` 与 `bom.xml`，并与工件、签名和版本号一起保存；原始聚合文件仅用于诊断，不能当作对外 SBOM。

## Docker 开发编排

`.docker/docker-compose.dev.yaml` 与 `.docker/Dockerfile.dev` 中的外部基础镜像都使用不可变 digest，`name: fw-dev` 不应修改。更新镜像时，先审阅目标镜像的官方 manifest 摘要，再同时更新 Dockerfile 或 Compose 中的 tag 与 digest；不能把 RustFS 等依赖改回 `latest`。

提交前至少运行：

```powershell
docker compose -f .docker/docker-compose.dev.yaml config -q
```

涉及应用镜像或运行行为的改动还应重建开发镜像并执行 Dev 验收测试。镜像 digest 的固定保证后续重建使用同一镜像内容，但不会自动替换已经运行的 `fw-dev` 容器。

完整 Dev 编排还要求在执行 `docker compose` 前设置至少 32 字符的 `FILEWEFT_DEV_PLATFORM_SHARED_SECRET`；它是 API/Worker 调用独立下游模拟器的系统凭据，不应提交到 `.env`、源码或测试报告。平台服务仅绑定 `127.0.0.1`，正常控制台访问通过认证后的 FileWeft API 镜像端点完成。
