
# Database Migration Strategy

Use Flyway or Liquibase.

Migration rules:

- never modify released migration
- add new version
- keep rollback strategy


Naming:

V001__create_file_object.sql

V002__create_document.sql


Large tables:

- operation log
- sync record
- task

require retention policy.
