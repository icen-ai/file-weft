
# Production Recovery Runbook


## Storage Failure

Symptoms:

file unavailable


Actions:

1 check storage health
2 verify reference
3 retry


## Connector Failure

Actions:

1 inspect SyncRecord
2 retry task
3 verify external id


## Tenant Leakage

Emergency:

disable tenant access

audit logs

verify repository filter
