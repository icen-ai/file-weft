---
route: "project/contributing"
group: "project"
order: 1
locale: "zh"
nav: "参与贡献"
title: "贡献功能，不侵蚀地基"
lead: "本页说明如何在为 FileWeft 添加代码、测试与文档的同时，保持 Core → SPI → Domain → Application → Persistence → Starter → Adapter 的分层完整，让框架保持可扩展且对 Java 友好。"
format: "markdown"
---

## 打开编辑器之前

任何贡献都应先从架构开始，而不是先新建类文件。

1. 阅读 `.ai/README_FINAL.md` 以及与主题直接相关的 `.ai/` 扩展手册。
2. 确定改动属于哪个模块。如果现有 SPI 无法容纳，先提议新增 SPI，再增加依赖。
3. 检查同样的问题是否能通过插件、适配器或宿主侧 Provider 解决，而不是改动框架内部。
4. 任何架构变化都要说明兼容影响、迁移步骤，以及运维人员将如何诊断失败。
5. 先开一个聚焦的 Issue 或 Draft PR，先描述边界，再描述实现。

> [!TIP] 优先扩展，而非修改
> 新增 `StorageAdapter`、`FileConnector`、`DoctorChecker` 或 `FileWeftTaskHandler` 可以放在独立适配器模块中，不会强迫所有宿主接受你的依赖。

## 改动该放在哪一层？

| 层 | 应放什么 | 不应放什么 |
| --- | --- | --- |
| `core` | 标识、结果、错误、事件、上下文 | Spring、ORM、厂商 SDK、业务规则 |
| `spi` | 租户、身份、授权、存储、连接器、工作流、任务、诊断契约 | 实现代码 |
| `domain` | Document、FileAsset、生命周期、版本、审计规则 | 基础设施调用、数据库查询 |
| `application` | 上传、发布、下线、审批、同步编排等用例 | 在事务中直接调用存储或连接器 |
| `persistence` | PostgreSQL 映射、仓储、Flyway 迁移 | 业务逻辑 |
| `starter` | Boot 2/3 自动装配与条件 Bean | 底层适配器逻辑 |
| `adapter` | MinIO/OSS/S3/Dify/ESE/AppBuilder 实现 | 核心业务规则 |

## 测试你触及的每一层

FileWeft 不接受未经测试的基础设施代码。按层提供对应证据。

1. **Core / Domain** —— 编写聚焦单元测试，覆盖不变量与错误路径。
2. **SPI** —— 增加契约测试，证明 Java 友好用法并暴露破坏性变更。
3. **Adapter / Persistence** —— 针对 PostgreSQL、对象存储或真实下游服务跑集成测试；仅 Mock 不够。
4. **Starter / Web** —— 增加 Spring Context 测试与 Boot 2/3 契约测试，确保 Bean 顺序与装配正确。
5. **Release** —— 更新兼容矩阵，运行 Compose 验收、浏览器 E2E 并校验 SBOM。

| 层 | 必要证据 |
| --- | --- |
| Core / Domain | 单元测试与不变量检查 |
| SPI | 契约测试与 Java 友好用法示例 |
| Adapter / Persistence | 真实集成测试 |
| Starter / Web | Context 与 Boot 2/3 契约测试 |
| Release | 兼容矩阵、Compose 验收、浏览器 E2E、SBOM |

## 提交让审阅者信得过的改动

- 使用 UTF-8 源文件、小而专注的类、显式构造器依赖。
- 持久化改动保留租户过滤、锁顺序与审计字段。
- 不在一次提交中改写无关的用户改动。
- 保持提交内聚，使用动作型提交信息，例如 `add circuit-breaker config for connector invocations`，而不是 `update`。
- 只为真实存在的行为添加或更新文档，不描述 wishful API。

一个最小的 SPI 贡献看起来像这样：

```kotlin
package ai.icen.fw.adapter.example

import ai.icen.fw.spi.storage.*
import java.io.InputStream
import java.time.Duration

class ExampleStorageAdapter : StorageAdapter {
    override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject {
        // 真实实现
    }

    override fun download(location: StorageObjectLocation): StorageDownload {
        // 真实实现
    }

    override fun delete(location: StorageObjectLocation) {}
    override fun exists(location: StorageObjectLocation): Boolean = false
    override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI = TODO()
    override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload = TODO()
    override fun uploadPart(upload: MultipartUpload, partNumber: Int, content: InputStream, contentLength: Long): MultipartPart = TODO()
    override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject = TODO()
    override fun abortMultipartUpload(upload: MultipartUpload) {}
}
```

通过插件或宿主 Bean 注册：

```kotlin
class ExamplePlugin : FileWeftPlugin {
    override fun id(): String = "example-storage"
    override fun storageAdapters(): List<StorageAdapter> = listOf(ExampleStorageAdapter())
}
```

> [!WARNING] 不要绕过 SPI
> 在 `domain` 或 `core` 中直接调用厂商 SDK 会破坏依赖方向，审阅时会被驳回。

## 常见问题

**能否直接在领域类里新增数据库列？**
不能。Schema 变更属于 `persistence` 的 Flyway 迁移；领域类表达业务规则，不表达存储布局。

**是否需要为 Boot 2 和 Boot 3 Starter 都写测试？**
是。任何 Starter 改动都需要在两端都有对应的 Context 或契约测试，除非改动本身就是代际相关的。

**安全问题应该在哪里报告？**
参见 [安全](project/security)。不要在公开 Issue 中披露疑似漏洞。

## 下一步

- 阅读 [SPI 参考](reference/spi)，找到与你想法匹配的契约。
- 查看 [模块边界](concepts/module-boundaries)，确认依赖方向。
- 浏览 [路线图](project/roadmap)，确认你的提案是否正在进行中。
