---
route: "concepts/security-model"
group: "concepts"
order: 4
locale: "en"
nav: "Security model"
title: "Fail-closed security model"
lead: "FileWeft does not guess who you are or what you can do. It delegates identity, tenant, and authorization to the host and treats a missing or ambiguous boundary as a denial. This page explains the three provider contracts, the fail-closed rule, and what stays out of public responses."
format: "markdown"
---

## 01. Three boundaries

FileWeft draws three security boundaries around every operation:

| Boundary | Provider | Question it answers |
|---|---|---|
| Identity | `UserRealmProvider` | Who is calling? |
| Tenant | `TenantProvider` | Which tenant does the call belong to? |
| Authorization | `AuthorizationProvider` | Is this action allowed on this resource? |

None of these answers come from HTTP parameters. They come from the host's own authentication and session context.

## 02. Identity and tenant providers

The host tells FileWeft who is calling and which tenant the call belongs to.

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
> User IDs are opaque strings. FileWeft preserves case, does not trim whitespace, and rejects ISO control characters. Normalize user IDs in the host before they reach FileWeft.

## 03. Authorization provider

The authorization request contains the subject, the resource (including its tenant), the action and optional environment attributes. The host evaluates it against its own ACL.

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

A request to publish a document might look like this inside the framework:

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

## 04. Fail-closed means "no" by default

FileWeft proceeds only when the complete security boundary exists. Missing context or ambiguous providers make the operation unavailable instead of silently broadening access.

| Situation | FileWeft behavior |
|---|---|
| No `TenantProvider` bean | Operations requiring tenant context return `FEATURE_UNAVAILABLE` |
| Two `TenantProvider` beans without explicit resolution | Operation fails fast; FileWeft does not guess |
| Tenant from token does not match resource tenant | `FORBIDDEN` |
| `AuthorizationProvider` denies the action | `FORBIDDEN` |
| Catalog provider cannot confirm safe mutation | `FEATURE_UNAVAILABLE` |

> [!WARNING]
> A fallback that says "everyone can read if the provider is missing" is a silent privilege escalation. FileWeft does not do that.

## 05. Public projections hide internals

HTTP responses are a deliberate projection of internal state. They omit:

- storage URLs and object keys
- connector internals and credentials
- raw Doctor evidence
- tenant identifiers
- unsafe diagnostic text

| Field | In internal model | In HTTP response |
|---|---|---|
| storage location | `StorageObjectLocation` | omitted |
| connector error stack trace | stored for ops | omitted |
| tenant id | internal identifier | omitted |
| Doctor raw evidence | full details | summarized, safe text only |

Audit views expose stable action and operator snapshots, not unrestricted `details` JSON.

## 06. Plugins are trusted code

A plugin shares the host JVM, permissions and classpath. It is not a sandbox.

```kotlin
@Component
class ReviewPlugin : FileWeftPlugin {
    override fun id() = "review-plugin"
    override fun reviewRouteProviders() = listOf(LineManagerRouteProvider())
}
```

Install only reviewed artifacts. Run untrusted extensions in a separate process behind authenticated, limited and audited protocols.

## 07. A request walks the boundaries

1. The host authenticates the caller.
2. `TenantProvider` resolves the tenant from the authenticated session.
3. `UserRealmProvider` resolves the user identity.
4. `AuthorizationProvider` checks the action against the resource and tenant.
5. Only if all three succeed does FileWeft execute the use case.

## FAQ

**Q: Can I pass `tenantId` as a query parameter for convenience?**
No. The tenant must come from the host's trusted authentication or session context. Request parameters are not a trusted source.

**Q: What happens if I only implement two of the three providers?**
Operations that need the missing boundary return `FEATURE_UNAVAILABLE`. The framework does not fall back to a default.

**Q: Should the authorization provider cache decisions?**
Caching is allowed, but never across tenants and never for sensitive environment attributes such as one-time tokens. Keep cache keys tenant-scoped.

## Next steps

- [Tenant & catalog isolation](./tenant-catalog.md) — how tenant context and folder authorization work together.
- [Architecture security](../architecture/security.md) — fail-closed design at every boundary.
- [Plugins](../extensions/plugins.md) — extend FileWeft with trusted in-process plugins.
