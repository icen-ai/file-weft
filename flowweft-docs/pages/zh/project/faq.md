---
route: "project/faq"
group: "project"
order: 7
locale: "zh"
nav: "常见问题"
title: "常见问题"
lead: "本页回答关于 FlowWeft 定位、架构、租户、存储与发布策略的最常见问题，并指向更深入的文档。"
format: "markdown"
---

## 通用

### FlowWeft 是什么？

FlowWeft 是面向企业的 Kotlin/JVM 文件智能基础设施框架，覆盖文档生命周期、审批、多目标交付、存储抽象、诊断与审计。它不是简单的上传模块、业务文档系统、Dify/ESE 包装器或云存储 SDK。

### FlowWeft 是一个开箱即用的文件上传组件吗？

不是。FlowWeft 提供基础能力；宿主需要通过 SPI 实现提供身份、租户、授权、目录树和业务规则。

## 架构与 SPI

### 必须使用 Spring Boot 吗？

不是必须，但官方 Starter 只支持 Spring Boot 2 与 Spring Boot 3。如果你能提供等价的生命周期与配置管理，也可以手动组装 runtime 与 web 模块。

### 可以添加自己的存储后端吗？

可以。实现 `StorageAdapter` SPI 并通过宿主 Bean 或插件注册。参见 [存储适配器指南](guides/storage-adapter)。

```kotlin
class MyStorageAdapter : StorageAdapter {
    override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject { ... }
    override fun download(location: StorageObjectLocation): StorageDownload { ... }
    override fun delete(location: StorageObjectLocation) { ... }
    override fun exists(location: StorageObjectLocation): Boolean { ... }
    override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI { ... }
    override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload { ... }
    override fun uploadPart(upload: MultipartUpload, partNumber: Int, content: InputStream, contentLength: Long): MultipartPart { ... }
    override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject { ... }
    override fun abortMultipartUpload(upload: MultipartUpload) { ... }
}
```

### 可以在领域代码中直接调用厂商 SDK 吗？

不能。`core`、`spi` 和 `domain` 不能依赖厂商 SDK。厂商代码应放在只依赖 SPI 的 `adapter` 模块中。

## 多租户

### FlowWeft 会创建租户吗？

不会。FlowWeft 从 `TenantProvider` 获取租户上下文，并将其应用到查询、存储路径、事件、任务、日志和缓存。

### 生产环境可以用 `fileweft.default-tenant-enabled=true` 吗？

不能。该属性是固定单租户的开发 fallback，不是生产多租户方案。

```properties
# 仅开发使用
fileweft.default-tenant-enabled=true
fileweft.default-tenant-id=tenant-a
```

> [!WARNING] 不信任请求参数中的 tenantId
> FlowWeft 从不信任调用方发送的 `tenantId`，始终通过配置的 `TenantProvider` 解析当前租户。

## 存储

### FlowWeft 会替代 S3 或 MinIO 吗？

不会。FlowWeft 通过 `StorageAdapter` 抽象存储，让你可以继续使用 S3、MinIO、OSS 或自定义后端，而不把厂商细节泄漏到领域层。

### 可以直接从对象存储提供文件访问吗？

FlowWeft 不在公共 HTTP API 中暴露对象存储预签名 URL。授权下载由应用以二进制流返回，响应头固定为 `attachment`、`nosniff` 和 `private, no-store`。

## 发布

### 当前稳定版是什么？

当前稳定版是 `ai.icen:*:0.0.3`，固定提交为 `dbf2a50fbca41e2ac5b5cf18bb44f9287c153637`。对应 `tag_push` 构建 `cnb-cl8-1jtgih45j` 已完成 12/12 流水线，19/19 个坐标的 POM、主 JAR 与校验和也已匿名回读。

### 还能使用 `com.fileweft:*` 制品吗？

不能。这些试推制品已撤回。只有远端完整证据已经具备的 `ai.icen:*` 版本才可使用。

### 如何确认 0.0.3 可以消费？

核对上面的不可变发布身份即可：标签 `v0.0.3`、提交 `dbf2a50fbca41e2ac5b5cf18bb44f9287c153637`、构建 `cnb-cl8-1jtgih45j`、12/12 流水线成功和 19/19 匿名制品回读。后续默认分支提交不会改变该发布身份。

## HTTP API

### `/api/**` 端点属于公共协议吗？

不属于。正式 HTTP 接口位于 `/fileweft/v1`。仅开发使用的 `/api/**` 路由不属于公共协议，可能随时变更。

### API 支持 Range 请求吗？

不支持。下载为完整二进制流，响应头固定。不支持 Range、HEAD、ETag 和 Content-Range。

## 下一步

- 阅读 [介绍](getting-started/introduction) 了解项目定位。
- 按照 [首次接入](getting-started/first-integration) 装配生产宿主。
- 查看 [路线图](project/roadmap) 了解哪些已证明、哪些在计划中。
