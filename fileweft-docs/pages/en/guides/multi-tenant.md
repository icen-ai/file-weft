---
route: "guides/multi-tenant"
group: "guides"
order: 8
locale: "en"
nav: "Multi-tenant"
title: "Implement tenant isolation"
lead: "Keep tenants safely separated across database queries, storage paths, events, tasks, logs, and caches by implementing the TenantProvider SPI."
format: "markdown"
---

FileWeft is built for multi-tenant deployments from the ground up. Tenant isolation is not a configuration flag; it is a cross-cutting responsibility that affects queries, storage paths, events, tasks, logs, and caches. This page shows how to provide tenant context and why you must never trust a tenant ID from a request parameter.

## 1. Provide tenant context

The only stable source of tenant identity is the `TenantProvider` SPI. FileWeft calls it whenever it needs the current tenant.

```kotlin
@Component
class SecurityTenantProvider : TenantProvider {

    override fun currentTenant(): TenantContext {
        val authentication = SecurityContextHolder.getContext().authentication
        val tenantId = authentication.details as? String
            ?: throw IllegalStateException("Tenant not resolved by host")
        return TenantContext(Identifier(tenantId))
    }
}
```

Typical host integrations derive the tenant from:

- A JWT claim issued by your identity provider.
- A custom HTTP header validated by your gateway.
- An mTLS client certificate attribute.
- A subdomain or path prefix mapped by your ingress.

> [!WARNING]
> Do not read `X-Tenant-Id` or any other client header directly inside FileWeft. Trust only the context established by your authentication layer.

## 2. Database query isolation

All FileWeft business tables include `tenant_id`. Repositories filter every query by the current tenant context. Even aggregate or count queries are scoped.

This means:

1. A missing `TenantProvider` causes operations to fail closed rather than defaulting to a shared tenant.
2. Two tenants with the same document number cannot see each other's drafts.
3. Audit logs, workflow tasks, and outbox events are all tenant-scoped.

## 3. Storage path isolation

Storage adapters receive the tenant ID in `StorageUploadRequest.tenantId`. A well-behaved adapter includes the tenant ID in object names or bucket prefixes:

```kotlin
private fun resolvePath(tenantId: Identifier, objectName: String): Path =
    root.resolve(tenantId.value).resolve(objectName)
```

For object stores such as S3, use a key prefix rather than a separate bucket per tenant unless your operational model specifically requires it:

```
s3://bucket/{tenantId}/documents/{objectName}
```

> [!NOTE]
> The tenant ID is also included in asset metadata, but object keys must remain tenant-scoped independently of metadata.

## 4. Events, tasks, logs, and caches

Tenant context flows through every asynchronous path:

| Concern | Isolation mechanism |
| --- | --- |
| Outbox events | Each event carries `tenantId`; workers process only events for the resolved tenant. |
| Background tasks | `fw_task` rows include `tenant_id`; handlers receive `TaskExecution.tenantId`. |
| Audit logs | Query endpoints filter by the current tenant context. |
| Caches | Cache keys must include the tenant ID. FileWeft does not provide a shared cross-tenant cache. |

## 5. Development fallback is not production multi-tenancy

For local development and single-node tests you can enable a fixed tenant:

```yaml
fileweft:
  default-tenant-enabled: true
  default-tenant-id: tenant-a
```

This fallback is useful for integration tests and demos, but it is not a production multi-tenant solution. In production:

1. Disable `default-tenant-enabled`.
2. Provide a real `TenantProvider` backed by your identity gateway.
3. Validate that every repository query includes `tenant_id` in the execution plan.

> [!WARNING]
> Do not present fixed-tenant or local-storage fallbacks as production multi-tenant solutions. They remove isolation and are intended for development only.

## 6. Failure modes

| Scenario | Behavior |
| --- | --- |
| TenantProvider returns null | Operation fails with `FEATURE_UNAVAILABLE` or `UNAUTHENTICATED`. |
| Tenant mismatch between URL and context | URL parameter is ignored; context tenant wins. |
| Cache key omits tenant ID | Risk of cross-tenant data leakage in your host cache. |
| Storage adapter ignores tenantId | Risk of cross-tenant object access. |

## FAQ

**Q: Can tenants share a database schema?**
Yes. FileWeft uses `tenant_id` row-level isolation. Whether you use one schema or many is a host operational choice.

**Q: Can I switch tenants inside one request?**
No. The tenant context is request-scoped. Batch operations across tenants must be split into separate requests or background jobs.

**Q: How do I test tenant isolation?**
Use `fileweft-testkit` to run the same operation with two different tenant contexts and assert that each sees only its own data.

## Next steps

- [Spring Boot hosting](spring-boot.md) to wire the `TenantProvider` bean.
- [Audit log](audit-log.md) to see how tenant context protects audit queries.
- [Storage adapter](storage-adapter.md) to implement tenant-scoped object paths.
