---
route: "concepts/security-model"
group: "concepts"
order: 4
locale: "zh"
nav: "安全模型"
title: "故障关闭安全模型"
lead: "FileWeft 不会猜测调用者是谁、能做什么。它把身份、租户和授权委托给宿主，并把缺失或歧义的安全边界视为拒绝。本页说明三个提供者契约、故障关闭规则，以及公共响应中不会暴露什么。"
format: "markdown"
---

## 01. 三道边界

FileWeft 为每个操作划定三道安全边界：

| 边界 | 提供者 | 它回答的问题 |
|---|---|---|
| 身份 | `UserRealmProvider` | 谁在调用？ |
| 租户 | `TenantProvider` | 调用属于哪个租户？ |
| 授权 | `AuthorizationProvider` | 该操作在该资源上是否被允许？ |

这三项答案都不来自 HTTP 参数，而是来自宿主自身的认证与会话上下文。

## 02. 身份与租户提供者

宿主告诉 FileWeft 谁在调用、属于哪个租户。

```kotlin
@Component
class HostTenantProvider(private val hostContext: HostContext) : TenantProvider {
    override fun currentTenant(): TenantContext = hostContext.currentTenant()
}

@Component
class HostUserRealmProvider(private val hostContext: HostContext) : UserRealmProvider {

    override fun currentUser(): UserIdentity? {
        val principal = hostContext.currentPrincipal() ?: return null
        return UserIdentity(
            id = Identifier(principal.userId),
            displayName = principal.displayName,
            attributes = mapOf("source" to principal.source)
        )
    }

    override fun findUser(userId: Identifier): UserIdentity? =
        currentUser()?.takeIf { it.id == userId }
}
```

> [!NOTE]
> 用户 ID 是不透明字符串。FileWeft 保留大小写、不裁剪空白、拒绝 ISO 控制字符。请在宿主侧完成用户 ID 归一化后再交给 FileWeft。

## 03. 授权提供者

授权请求包含主体、资源（含租户）、操作和可选的环境属性。宿主用自身的 ACL 进行评估。

```kotlin
@Component
class HostAuthorizationProvider(private val hostAcl: HostAcl) : AuthorizationProvider {

    override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
        val allowed = hostAcl.permits(
            tenantId = request.resource.tenantId,
            subjectId = request.subject.id,
            subjectType = request.subject.type,
            action = request.action.name,
            resourceType = request.resource.type,
            resourceId = request.resource.id,
            environment = request.environment.attributes
        )
        return AuthorizationDecision(allowed)
    }
}
```

框架内部一份发布文档的请求可能长这样：

```kotlin
val request = AuthorizationRequest(
    subject = AuthorizationSubject(Identifier("user-007"), "user"),
    resource = AuthorizationResource(
        id = Identifier("doc-123"),
        type = "document",
        tenantId = Identifier("tenant-a")
    ),
    action = AuthorizationAction("publish"),
    environment = AuthorizationEnvironment(mapOf("ip" to "203.0.113.4"))
)
```

## 04. 故障关闭意味着默认拒绝

FileWeft 只在完整安全边界存在时才执行操作。上下文缺失或提供者歧义会让操作不可用，而不是静默扩大访问。

| 情况 | FileWeft 行为 |
|---|---|
| 缺少 `TenantProvider` Bean | 需要租户上下文的操作返回 `FEATURE_UNAVAILABLE` |
| 存在两个未明确解析的 `TenantProvider` Bean | 操作快速失败；FileWeft 不猜测 |
| Token 中的租户与资源租户不一致 | `FORBIDDEN` |
| `AuthorizationProvider` 拒绝操作 | `FORBIDDEN` |
| 目录提供者无法确认安全变更能力 | `FEATURE_UNAVAILABLE` |

> [!WARNING]
> “如果提供者缺失就允许所有人读取”这种 fallback 是静默提权。FileWeft 不会这样做。

## 05. 公共投影隐藏内部信息

HTTP 响应是内部状态的有意投影，会省略：

- 存储 URL 与对象键
- 连接器内部信息与凭据
- Doctor 原始证据
- 租户标识
- 不安全的诊断文本

| 字段 | 内部模型 | HTTP 响应 |
|---|---|---|
| 存储位置 | `StorageObjectLocation` | 省略 |
| 连接器错误堆栈 | 运维侧存储 | 省略 |
| 租户 ID | 内部标识 | 省略 |
| Doctor 原始证据 | 完整详情 | 仅汇总安全文本 |

审计视图暴露稳定的动作与操作者快照，而不是无限制的 `details` JSON。

## 06. 插件是可信代码

插件与宿主共享 JVM、权限和类路径，不是沙箱。

```kotlin
@Component
class ReviewPlugin : FileWeftPlugin {
    override fun id() = "review-plugin"
    override fun reviewRouteProviders() = listOf(LineManagerRouteProvider())
}
```

只安装经过评审的制品。不可信扩展应放在独立进程，通过鉴权、限流、审计的协议接入。

## 07. 请求如何穿越三道边界

1. 宿主认证调用者。
2. `TenantProvider` 从认证会话解析租户。
3. `UserRealmProvider` 解析用户身份。
4. `AuthorizationProvider` 针对资源与租户检查操作权限。
5. 只有三项全部通过，FileWeft 才会执行用例。

## 常见问题

**Q：为了方便，可以把 `tenantId` 放在查询参数里吗？**
不可以。租户必须来自宿主的可信认证或会话上下文。请求参数不是可信来源。

**Q：只实现三个提供者中的两个会怎样？**
需要缺失边界的操作会返回 `FEATURE_UNAVAILABLE`。框架不会使用默认值回退。

**Q：授权提供者可以缓存决策吗？**
可以缓存，但不要跨租户缓存，也不要缓存一次性令牌等敏感环境属性。缓存键必须限定在租户内。

## 下一步

- [租户与目录隔离](./tenant-catalog.md)——租户上下文与目录授权如何协同。
- [架构安全](../architecture/security.md)——每个边界的故障关闭设计。
- [插件](../extensions/plugins.md)——用可信的进程内插件扩展 FileWeft。
