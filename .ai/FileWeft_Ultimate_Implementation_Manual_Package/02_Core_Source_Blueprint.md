
# Core Source Blueprint

Package:
ai.icen.fw.core

Contains:

- Identifier
- Result
- ErrorCode
- DomainEvent
- TenantContext
- TraceContext


Core must not contain:

- Spring annotation
- ORM
- HTTP client
- cloud SDK


Core is the longest lived layer.
