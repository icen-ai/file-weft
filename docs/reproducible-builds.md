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

该任务通过 CycloneDX Gradle 插件聚合所有子模块已解析的依赖，并在 `build/reports/cyclonedx/bom.json` 与 `build/reports/cyclonedx/bom.xml` 生成 JSON/XML 物料清单；`verifySbom` 会拒绝空文件或不声明 `bomFormat: CycloneDX` 的 JSON。物料清单包含生成时刻和构建序列号，属于本次发布的审计证据而非应提交的可复现源码文件。发布系统应将两份文件与工件、签名和版本号一起归档。

## Docker 开发编排

`.docker/docker-compose.dev.yaml` 与 `.docker/Dockerfile.dev` 中的外部基础镜像都使用不可变 digest，`name: fw-dev` 不应修改。更新镜像时，先审阅目标镜像的官方 manifest 摘要，再同时更新 Dockerfile 或 Compose 中的 tag 与 digest；不能把 RustFS 等依赖改回 `latest`。

提交前至少运行：

```powershell
docker compose -f .docker/docker-compose.dev.yaml config -q
```

涉及应用镜像或运行行为的改动还应重建开发镜像并执行 Dev 验收测试。镜像 digest 的固定保证后续重建使用同一镜像内容，但不会自动替换已经运行的 `fw-dev` 容器。
