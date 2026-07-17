---
route: "getting-started/introduction"
group: "getting-started"
order: 1
locale: "zh"
nav: "介绍"
title: "为必须长久运行的文件系统打地基"
lead: "了解 FlowWeft 的定位、边界与三种接入方式，找到适合你团队的起点。"
format: "markdown"
---

## 这页讲什么

大多数团队都是从“简单上传”开始的：一个接口、一个对象存储桶、一条数据库记录。随后需求接踵而至：版本管理、审批流程、审计追溯、生命周期规则、多租户隔离、下游交付。业务规则与厂商 SDK 调用越缠越紧，代码也越来越难维护。

FlowWeft 是一个面向企业的 Kotlin/JVM 文件基础设施框架，为文档生命周期、存储抽象、审批、交付与诊断提供稳定底座，同时不接管你的身份提供商、目录结构或业务策略。

> [!TIP]
> 把 FlowWeft 看作“机舱”而非“驾驶舱”：它保证文件机械系统可靠运转；谁能进来、文件代表什么业务含义，由宿主决定。

## FlowWeft 不是什么

| 不是这个 | 原因 |
| --- | --- |
| 简单上传模块 | 它负责版本、生命周期、审计与交付编排。 |
| 业务文档系统 | 不内置你的审批矩阵或目录分类。 |
| Dify / ESE 包装器 | 连接器是可插拔 SPI，不是硬编码的厂商集成。 |
| 云存储 SDK | 存储通过 `StorageAdapter` 抽象，可用 MinIO、S3、OSS 或自实现。 |

## FlowWeft 负责什么，宿主负责什么

| FlowWeft 负责 | 宿主负责 |
| --- | --- |
| 文档、版本和交付状态 | 认证与用户目录 |
| Outbox、任务租约和审计证据 | 目录拓扑与目录 ACL |
| 稳定的存储及连接器契约 | 业务策略与展示层 |
| 租户作用域标识与隔离 | 真实租户解析（Header、JWT、路径等） |

## 设计立场

FlowWeft 基于几条不可妥协的假设：

1. **外部系统不可靠。** 存储桶、下游连接器、AI 服务都会超时或报错。FlowWeft 先提交本地业务状态，在同一事务记录持久任务，再在长事务之外调用外部系统。
2. **SPI 优先。** 存储、身份、授权、租户、目录、工作流、连接器、AI 行为都通过契约进入；Core 与 Domain 不依赖 Spring、数据库或厂商 SDK。
3. **故障关闭。** 缺失租户上下文或歧义 Provider 会让操作不可用，不会静默扩大访问范围。
4. **Doctor 是一等公民。** 主要组件都暴露诊断能力，让运维在用户之前发现问题。

> [!NOTE]
> FlowWeft 以 JDK 8 为基线，并在 JDK 21 验证。公共 API 保持 Java 友好：SPI 契约中不使用 `suspend`、`Flow`、`value class`、`sealed interface` 或 `data object`。

## 选择接入方式

FlowWeft 提供三种接入方式，按你对控制力的需求选择：

| 接入方式 | 适合场景 | 你需要做什么 |
| --- | --- | --- |
| **仅 SPI** | 类库或自定义运行时 | 直接实现或使用 `ai.icen.fw.spi` 契约。 |
| **Runtime Starter** | 需要编程式 API 的 Spring Boot 宿主 | 引入 `fileweft-spring-boot2-starter` 或 `fileweft-spring-boot3-starter`，自动配置持久化、Worker 与应用服务。 |
| **Web Starter** | 需要 REST API 的宿主 | 引入 `fileweft-web-spring-boot2-starter` 或 `fileweft-web-spring-boot3-starter`，暴露稳定的 `/fileweft/v1` 接口。 |

> [!WARNING]
> 不要在同一应用混用 Boot 2 与 Boot 3 Starter。选择一个代际，并让所有 FlowWeft 制品与之对齐。

## 下一步

- [安装 FlowWeft 0.0.3](installation.md)
- [接入生产宿主](first-integration.md)
- [5 分钟快速开始](quickstart.md)
