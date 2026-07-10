
# SPI Complete Contract

## StorageAdapter

Responsibilities:
- upload
- stream download
- delete
- exists
- url
- multipart


## UserRealmProvider

FileWeft does not own users.


## AuthorizationProvider

ABAC:
Subject
Resource
Action
Environment


## TenantProvider

Provides tenant context.


## FileConnector

External integration boundary.

Required:
timeout
retry
idempotency
health check

