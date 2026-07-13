---
route: "getting-started/introduction"
group: "getting-started"
order: 1
locale: "en"
nav: "Introduction"
title: "Infrastructure for files that must endure"
lead: "Understand what FileWeft is, where its responsibilities end, and which integration path fits your team."
format: "markdown"
---

## What problem does FileWeft solve?

Most teams start with a simple file upload: a controller, a bucket, a database row. Then they need versions, approvals, audit trails, lifecycle rules, multi-tenant isolation, and downstream delivery. Each feature is bolted on, and the code becomes a tangle of business rules and vendor SDK calls.

FileWeft is a Kotlin/JVM infrastructure framework that gives you a stable foundation for document lifecycles, storage abstraction, approvals, delivery, and diagnostics — without taking over your identity provider, folder tree, or business policies.

> [!TIP]
> Think of FileWeft as the engine room, not the captain's bridge. It keeps the file machinery reliable; your host decides who can enter and what the files mean.

## What FileWeft is not

| Not this | Why |
| --- | --- |
| A simple upload module | It owns versions, lifecycle, audit, and delivery orchestration. |
| A business-specific document system | It does not encode your approval matrix or folder taxonomy. |
| A Dify / ESE wrapper | Connectors are pluggable SPIs, not hard-wired vendor integrations. |
| A cloud storage SDK | Storage is abstracted behind `StorageAdapter`; you bring MinIO, S3, OSS, or your own. |

## What FileWeft owns vs. what your host owns

| FileWeft owns | Your host owns |
| --- | --- |
| Document, version, and delivery state | Authentication and user directory |
| Outbox, task leases, and audit evidence | Folder topology and folder ACL |
| Stable storage and connector contracts | Business-specific policy and presentation |
| Tenant-scoped identifiers and isolation | The real tenant resolver (header, JWT, path, etc.) |

## Design posture

FileWeft is built around a few non-negotiable assumptions:

1. **External systems fail.** Storage buckets, downstream connectors, and AI services will timeout or return errors. FileWeft commits local business state first, records durable work in the same transaction, and calls external systems outside long-running database transactions.
2. **SPI first.** Storage, identity, authorization, tenant, catalog, workflow, connector, and AI behavior enter through contracts. Core and Domain do not depend on Spring, databases, or vendor SDKs.
3. **Fail closed.** Missing tenant context or ambiguous provider resolution makes an operation unavailable — FileWeft does not silently expand access.
4. **Doctor is a first-class feature.** Major components expose diagnostics so operators can see problems before users do.

> [!NOTE]
> FileWeft targets JDK 8 as a baseline and is verified on JDK 21. Public APIs stay Java-friendly: no `suspend`, `Flow`, `value class`, `sealed interface`, or `data object` in SPI contracts.

## Choose your entry point

FileWeft offers three ways to plug in. Pick the one that matches your appetite for control:

| Entry point | Best for | What you add |
| --- | --- | --- |
| **SPI only** | Libraries or custom runtimes | Implement or consume `ai.icen.fw.spi` contracts directly. |
| **Runtime Starter** | Spring Boot hosts that want programmatic APIs | `fileweft-spring-boot2-starter` or `fileweft-spring-boot3-starter` wires persistence, workers, and application services. |
| **Web Starter** | REST API consumers | `fileweft-web-spring-boot2-starter` or `fileweft-web-spring-boot3-starter` exposes the stable `/fileweft/v1` surface. |

> [!WARNING]
> Do not mix Boot 2 and Boot 3 starters in the same application. Choose one generation and align every FileWeft artifact to it.

## Next steps

- [Install FileWeft 0.0.1](installation.md)
- [Wire a production host](first-integration.md)
- [Run the 5-minute quickstart](quickstart.md)
