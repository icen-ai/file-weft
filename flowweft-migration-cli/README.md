# FlowWeft migrate-and-exit CLI

`flowweft-migration-cli` is the one-shot database release boundary for FlowWeft. It composes the
existing migration owners instead of copying SQL resources. A successful process exits after every
selected line has migrated or validated; Web and Worker processes should then start with their own
runtime migration mode set to `validate`.

## Owned migration lines

The current fixed order is:

1. `legacy` — `classpath:ai/icen/fw/db/migration/{dialect}` with
   `fileweft_schema_history`;
2. `workflow` — `classpath:ai/icen/fw/workflow/db/migration/{dialect}` with
   `flowweft_workflow_schema_history`.

`--lines=all` expands to that order. Explicit multi-line input must be `legacy,workflow`; duplicates,
reversed order and unknown lines fail before opening a connection. Workflow-only installation uses
`--lines=workflow`, requires a pre-created schema/database, and never creates legacy file or old Agent
tables. The future Agent persistence line is not advertised until its dedicated runner, resources,
history and three-dialect tests exist; when it lands it must be added to this fixed order and to the
same fresh/upgrade/isolation contracts.

Each SQL migration has exactly one resource owner. Do not copy Workflow scripts into the legacy
location, combine histories, run `repair`, enable `baselineOnMigrate`, or synthesize successful rows.

## Configuration

Every setting can be supplied through the environment. Non-secret `--name=value` arguments take
precedence where shown.

| Purpose | Argument | Environment |
| --- | --- | --- |
| JDBC URL | `--url` | `FLOWWEFT_MIGRATION_JDBC_URL` |
| DDL user | `--user` | `FLOWWEFT_MIGRATION_JDBC_USER` |
| exact schema/database | `--schema` | `FLOWWEFT_MIGRATION_SCHEMA` |
| operation | `--mode=migrate\|validate` | `FLOWWEFT_MIGRATION_MODE` |
| lines | `--lines=legacy\|workflow\|legacy,workflow\|all` | `FLOWWEFT_MIGRATION_LINES` |
| create legacy schema | `--create-schema=true\|false` | `FLOWWEFT_MIGRATION_CREATE_SCHEMA` |
| UTF-8 password file | `--password-file` | `FLOWWEFT_MIGRATION_JDBC_PASSWORD_FILE` |
| password fallback | forbidden | `FLOWWEFT_MIGRATION_JDBC_PASSWORD` |

Prefer a read-only mounted secret file. The CLI reads at most 16 KiB, accepts one final LF/CRLF,
copies the password only at the JDBC boundary and overwrites mutable byte/character buffers when
they leave scope. A password is never accepted in process arguments. Credential-bearing URL
authority or query properties (including encoded/vendor password, secret, token, credential and
access-key names) are rejected. Failure output never includes the exception, JDBC URL, SQL, file
path, user or password. Do not enable JDBC/Flyway debug logging in a shared log sink.

The JDBC URL must already select the exact schema asserted by `FLOWWEFT_MIGRATION_SCHEMA`:

- PostgreSQL/KingbaseES: use the driver `currentSchema` property. `create-schema=true` is permitted
  only when `legacy` runs first; production normally pre-creates the schema and leaves it false.
- MySQL 8: put the pre-created database in the URL path. The CLI never creates a MySQL database.
- Workflow-only: pre-create the target for all three dialects and keep `create-schema=false`.

## Stable process result

| Exit | Meaning | Automation action |
| ---: | --- | --- |
| `0` | selected lines completed and the result JSON was written to stdout | proceed to runtime `validate` |
| `2` | invalid/missing configuration or unavailable selected JDBC driver | fix Job configuration; no migration action was created |
| `10` | migration/validation/runtime linkage failed, including an unknown commit outcome | stop rollout and inspect protected DB/Flyway evidence |

Success is one compact JSON object with `mode`, fixed `lines`, per-line counts and total count. A
migration failure is a credential-free JSON object with stable code `MIGRATION_FAILED` and the
`failedLine`. No stack trace is printed. A failed Job must not be transformed into success or blindly
retried; first inspect the dedicated history and database state. Re-running `migrate` after a proven
successful run is idempotent and reports zero migrations.

## Upgrade and release procedure

1. Stop incompatible write paths, drain old nodes, make a recoverable database backup and verify the
   restore path.
2. Confirm the existing 0.0.3 legacy history is the dedicated `fileweft_schema_history`, successful
   through V029, with no checksum mismatch or legacy rows in `flyway_schema_history`.
3. Run one migration Job with `mode=migrate`, `lines=all` and a short-lived DDL credential.
4. Require exit `0`, retain the safe result JSON, then run a separate `mode=validate`, `lines=all`
   invocation with the intended validation credential.
5. Revoke the DDL credential and start all application roles with runtime validation enabled.

Older trial schemas that mixed FlowWeft and host migrations in `flyway_schema_history` require a DBA
conversion in an isolated copy; this CLI intentionally refuses automatic adoption or repair.

## Container and Kubernetes Job

Build the locked application distribution and then the minimal non-root runtime image from repository
root:

```powershell
.\gradlew.bat :flowweft-migration-cli:installDist
docker build -f flowweft-migration-cli/Dockerfile -t <registry>/flowweft-migration-cli:<version> .
```

Publish by immutable digest. Copy `kubernetes/job.example.yaml`, replace its deliberately invalid image
digest, and create the referenced ConfigMap and Secret through the platform's audited secret flow. The
Secret must contain a `password` key; never commit its value. The example disables service-account
tokens, runs as UID/GID 65532, mounts only a read-only password file, drops Linux capabilities, uses a
read-only root filesystem plus a bounded ephemeral `/tmp`, runs one pod with `backoffLimit: 0`, and
enforces a deadline. Add a namespace-specific NetworkPolicy permitting only DNS (if required) and the
selected database endpoint.

The image is JDK 21 LTS while bytecode remains Java 8 compatible. Release closure still requires the
repository Java 8/17/21/25 lanes, real PostgreSQL/MySQL/Kingbase jobs, and an actual container Job using
the exact release image digest and commit SHA.
