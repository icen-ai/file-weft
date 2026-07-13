---
route: "project/security"
group: "project"
order: 2
locale: "en"
nav: "Security"
title: "Report security issues privately"
lead: "This page explains how to disclose a suspected vulnerability in FileWeft so it can be investigated and fixed before it becomes public knowledge."
format: "markdown"
---

## What counts as a security issue

Report anything that could weaken tenant isolation, bypass authorization, leak object storage credentials, forge workflow decisions, or expose user data unexpectedly. Examples include:

- A way to read or mutate another tenant's documents without the correct `AuthorizationProvider` decision.
- A code path that trusts `tenantId` directly from request parameters instead of from `TenantProvider`.
- Leakage of signed object-storage URLs, database credentials or private keys in logs or error responses.
- A reproducible crash or resource exhaustion that affects shared workers or the database.
- Bypass of lifecycle transitions, such as publishing a document without required approvals.

## How to report privately

Do not open a public issue, discussion, pull request comment or social-media post about a suspected vulnerability.

1. Compose a minimal, reproducible report.
2. Email it to **support@icen.ai** with the subject prefix `[SECURITY] FileWeft`.
3. Include a safe way for the maintainers to contact you for coordinated follow-up.
4. Allow reasonable time for investigation and remediation before any public disclosure.

> [!WARNING] Public disclosure puts everyone at risk
> A vulnerability shared in public before a fix is available can be exploited against production hosts before they can upgrade.

## What to include in your report

A good report saves days of back-and-forth. Include as many of the following as you can:

- Affected FileWeft version and module (`fileweft-core`, `fileweft-web-api`, a specific starter, etc.).
- Deployment assumptions: Spring Boot generation, database, storage backend, authentication scheme.
- Minimal reproduction steps or a proof-of-concept that a reviewer can run locally.
- Observed impact and any known mitigation.
- Your preferred secure contact method for coordinated disclosure.

```text
Subject: [SECURITY] FileWeft - possible tenant isolation bypass in workflow decisions

Affected version: ai.icen:fileweft-web-api:0.0.2
Module: fileweft-web-runtime
Deployment: Spring Boot 3.2, PostgreSQL 16, MinIO

Summary:
When calling POST /fileweft/v1/documents/{id}/submit with a crafted header,
the request appears to skip the AuthorizationProvider check under condition X.

Reproduction:
1. Start the sample host with the default in-memory authorization provider.
2. Create document D as tenant A.
3. Send the submit request with header X set to ...
4. Observe that the workflow transitions without an authorization decision.

Impact:
An authenticated user from tenant A may trigger lifecycle changes without
permission evaluation.

Mitigation known to reporter:
None.

Contact: reporter@example.com (PGP key attached)
```

## Protect sensitive evidence

Remove production credentials, tenant data, user identifiers, object keys, internal endpoints and private stack traces. Use synthetic fixtures whenever possible.

Never send the following in an issue, log excerpt, browser recording or chat message:

- Live access tokens or session cookies.
- Database connection strings or passwords.
- Object storage access-key/secret-key pairs.
- Real tenant IDs, document IDs or user IDs.
- Private keys or certificate material.

## Disclosure timeline

The FileWeft maintainers follow a coordinated disclosure process:

1. **Acknowledgement** — you will receive a response within five business days confirming receipt.
2. **Investigation** — maintainers reproduce the issue and assess impact.
3. **Fix and verification** — a patch is prepared, tested against the supported matrix and privately shared with you.
4. **Release** — a fixed version is published and documented.
5. **Public disclosure** — after hosts have had time to upgrade, a security advisory is published with credit to the reporter.

> [!NOTE] General support is also welcome at support@icen.ai
> Usage questions may use the same address, but please mark suspected vulnerabilities clearly as private and security-sensitive.

## FAQ

**Will I receive a CVE?**
If the issue meets the criteria for a CVE, the maintainers will request one and credit you as the reporter.

**Can I disclose after 90 days?**
The project prefers coordinated disclosure. If progress stalls, contact the maintainers to agree on a reasonable timeline.

**Does FileWeft offer a bug bounty?**
There is no formal bounty program at this time. Responsible disclosure is still appreciated and acknowledged.

## Next steps

- Read the [security architecture](architecture/security) page to understand how FileWeft fails closed at boundaries.
- Review the [HTTP API reference](reference/http-api) to confirm which endpoints require which permissions.
