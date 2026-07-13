---
route: "extensions/plugins"
group: "extensions"
order: 1
locale: "zh"
nav: "插件开发"
title: "编写生产级插件"
lead: "学习如何将 FileWeft 扩展打包成单个插件制品，让运维人员无需修改核心代码就能发现连接器、存储适配器、Doctor 检查器和任务处理器。"
format: "markdown"
---

FileWeft 插件是若干现有 SPI 贡献的聚合包。它不会形成新的架构层，也**不是**安全沙箱。当你需要新增下游连接器、自定义存储后端或诊断检查器时，首先应考虑能否以插件形式通过 SPI 贡献，而不是改动核心代码。

## 1. 插件与直接声明 Spring Bean 的对比

你可以直接以 Spring Bean 形式贡献许多 FileWeft 能力，但插件更适合可复用、持有厂商 SDK 或需要进入公共清单的扩展。

| 方式 | 适用场景 | 权衡 |
| --- | --- | --- |
| 直接 Spring Bean | 应用内的一次性定制 | 与应用代码混在一起，难以共享 |
| `FileWeftPlugin` | 可复用扩展、SDK 隔离、公共清单 | 必须遵守 SPI 契约；属于受信任的进程内代码 |

## 2. 插件能贡献什么

`FileWeftPlugin` 接口暴露了所有扩展点。FileWeft 在构建注册表时只调用每个 getter 一次，随后保存不可变快照。

| 方法 | 能力 | 典型用途 |
| --- | --- | --- |
| `storageAdapters()` | 自定义对象存储 | MinIO、OSS、S3 或私有存储 |
| `connectors()` | 下游交付 | 合规归档、检索索引、AI 平台 |
| `doctorCheckers()` | 运行诊断 | 校验凭据、端点和权限 |
| `agents()` / `agentTaskTriggers()` | AI 自动化 | 文档分类或抽取触发器 |
| `outboxEventHandlers()` | 异步响应 | 在请求线程外响应生命周期事件 |
| `taskHandlers()` | 后台任务 | 批量迁移、清理或导入任务 |
| `reviewRouteProviders()` | 审批路由 | 租户特定的审批工作流 |

> **NOTE**  
> 贡献 getter 在注册表构造期间被调用。不要在其中执行网络调用、数据库写入或其他业务副作用。远程工作应放在 FileWeft 随后调用的连接器、处理器或 Doctor 方法中。

## 3. 最小插件实现

添加 SPI 依赖：

```kotlin
// build.gradle.kts
dependencies {
    compileOnly("ai.icen:fileweft-spi:0.0.1")
}
```

实现插件并暴露一个连接器和一个 Doctor 检查器：

```kotlin
package com.example.fileweft.ext

import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.doctor.DoctorChecker
import ai.icen.fw.spi.plugin.FileWeftPlugin
import org.springframework.stereotype.Component

@Component
class AcmeArchivePlugin(
    private val archiveConnector: AcmeArchiveConnector,
    private val archiveDoctorChecker: AcmeArchiveDoctorChecker,
) : FileWeftPlugin {

    override fun id(): String = "acme-archive-plugin"

    override fun connectors(): Map<String, FileConnector> =
        mapOf("acmeArchive" to archiveConnector)

    override fun doctorCheckers(): List<DoctorChecker> =
        listOf(archiveDoctorChecker)
}
```

连接器本身必须实现 `FileConnector` 契约（详见 [连接器工程](/extensions/connectors)）：

```kotlin
package com.example.fileweft.ext

import ai.icen.fw.spi.connector.*
import java.util.concurrent.ConcurrentHashMap

class AcmeArchiveConnector : FileConnector {
    private val externalIds = ConcurrentHashMap<String, String>()

    override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult {
        val externalId = externalIds.computeIfAbsent(request.businessId.toString()) {
            "acme-${System.currentTimeMillis()}"
        }
        return ConnectorSyncResult(
            status = ConnectorSyncStatus.SUCCESS,
            externalId = externalId,
            message = "Archived to Acme storage.",
        )
    }

    override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult {
        externalIds.remove(request.businessId.toString())
        return ConnectorSyncResult(
            status = ConnectorSyncStatus.SUCCESS,
            message = "Removed from Acme storage.",
        )
    }

    override fun health(): ConnectorHealth =
        ConnectorHealth(status = ConnectorHealthStatus.HEALTHY)
}
```

Doctor 检查器以无副作用的方式校验配置：

```kotlin
package com.example.fileweft.ext

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.doctor.DoctorChecker

class AcmeArchiveDoctorChecker(
    private val baseUrl: String,
) : DoctorChecker {

    override fun name(): String = "acme-archive"

    override fun check(context: DoctorCheckContext): DoctorCheckResult {
        if (baseUrl.isBlank()) {
            return DoctorCheckResult(
                checkerName = name(),
                status = DoctorStatus.ERROR,
                reason = "Acme archive base URL is not configured.",
                repairSuggestion = "Set acme.archive.base-url in application.yml.",
            )
        }
        return DoctorCheckResult(
            checkerName = name(),
            status = DoctorStatus.HEALTHY,
            reason = "Acme archive base URL is configured.",
        )
    }
}
```

## 4. 发现规则与优先级

FileWeft 从两个来源发现插件：

1. **Spring Bean** —— FileWeft starter 会自动收集所有 `FileWeftPlugin` Bean。
2. **Java `ServiceLoader`** —— 在 `META-INF/services/ai.icen.fw.spi.plugin.FileWeftPlugin` 中放置提供者文件：

    ```text
    # META-INF/services/ai.icen.fw.spi.plugin.FileWeftPlugin
    com.example.fileweft.ext.AcmeArchivePlugin
    ```

当两种来源暴露同一个插件类时，Spring Bean 优先。插件 ID 或连接器 ID 冲突会在启动时立即报错。

> **WARNING**  
> 插件是受信任的进程内代码。只加载经过审查的插件，因为它们与宿主应用共享同一个类路径和内存空间。

贡献重叠时的优先级：

| 来源 | 优先于 |
| --- | --- |
| 宿主 Spring Bean | 插件 Bean |
| 插件 Bean | 框架默认 |
| 先发现的插件 | 后发现的同名 ID 插件（启动报错） |

## 5. 验证插件

发布插件前，请完成以下检查清单：

1. **单元测试**插件决策和辅助逻辑。
2. **为每个存储适配器和连接器运行 SPI 契约测试**。
3. **启动匹配的 starter 上下文**（`fileweft-spring-boot2-starter` 或 `fileweft-spring-boot3-starter`），确认应用能正常启动。
4. **通过公共端点查看清单**：

    ```bash
    curl -s http://localhost:8080/fileweft/v1/plugins | jq .
    ```

    正常响应示例：

    ```json
    {
      "code": "OK",
      "message": "OK",
      "data": [
        {
          "id": "acme-archive-plugin",
          "capabilities": [
            { "type": "CONNECTOR", "count": 1 },
            { "type": "DOCTOR_CHECKER", "count": 1 }
          ]
        }
      ],
      "error": null
    }
    ```

5. **运行 Doctor 检查**，确认缺失配置或不可达依赖会返回可操作的诊断信息，而不是堆栈跟踪。

## 常见问题

**插件能覆盖框架默认实现吗？**  
可以。与框架默认实现 ID 相同的宿主 Bean 或插件 Bean 会优先生效。ID 冲突会立即失败，提醒运维人员。

**我应该在 `connectors()` 或 `storageAdapters()` 里发起远程调用吗？**  
不应该。这些 getter 只在启动时调用一次，应保持轻量和确定性。网络操作应放在 `FileConnector.sync()`、`DoctorChecker.check()` 或事件处理器中。

**插件可以拥有自己的配置属性吗？**  
可以。插件就是普通 Spring Bean，可以注入 `@Value` 或 `@ConfigurationProperties`。厂商 SDK 和凭据应封装在插件内部，切勿泄漏到 SPI 或 core。

## 下一步

- [连接器工程](/extensions/connectors) —— 编写能在不可靠下游系统中生存的 `FileConnector`。
- [Doctor](/doctor) —— 添加运维人员可操作的诊断能力。
- [生命周期](/lifecycle) —— 理解 sync、remove 和 outbox 事件在何时触发。
