
# Core Source Blueprint

Package:
com.fileweft.core

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
