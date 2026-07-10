
# Controller DTO API Specification


Controller responsibilities:

- request validation
- DTO conversion
- call application service


Never:

- call adapter
- modify entity


Unified response:


code

message

data

traceId


Important APIs:


/document/{id}/doctor

/document/{id}/sync-status

/document/{id}/versions

/document/{id}/logs
