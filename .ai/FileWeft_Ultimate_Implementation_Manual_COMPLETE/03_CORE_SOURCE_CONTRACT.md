
# Core Source Contract

Core classes:

- Result
- ErrorCode
- DomainEvent
- Identifier
- TenantContext
- TraceContext

Core cannot import:
Spring
ORM
Vendor SDK


Example:

interface DomainEvent {
 String getId();
 long getTimestamp();
}

