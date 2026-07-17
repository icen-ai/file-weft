# FlowWeft Workflow SLA Runtime

This additive module turns the reference-template SLA metadata into a typed,
durable orchestration boundary. Existing Workflow contracts could calculate an
authorized business-calendar instant, but they did not bind that evidence to a
human task or provide a crash-safe reminder/escalation lifecycle.

The runtime binds every schedule to the exact definition revision, node,
calendar, calendar-profile digest, action-profile digest and authoritative task
snapshot. Creation follows `PREPARE authorization -> external calendar calls ->
task reread -> COMMIT authorization -> guarded durable insert`. Store methods
are individual short transaction boundaries; calendar and action calls happen
between them.

No action can approve, reject, claim, delegate or otherwise decide a task. The
only built-in milestone mapping is reminder, due-breach incident and escalation,
all delegated to `WorkflowSlaActionPort`. The port must recheck current task and
audience authority before a side effect. Terminal tasks and denied current
authority are suppressed.

This slice intentionally defines the durable store, lease/fence, provider-call
checkpoint, outcome-unknown and reconciliation contracts without selecting a
database. A later JDBC adapter must implement the task guard and schedule write
atomically in the Workflow schema and prove PostgreSQL, MySQL and KingbaseES
behavior. Until that adapter is installed, SLA capability must be reported as
unsupported rather than falling back to in-memory timers.

Compatibility is additive: no released FileWeft ABI, V001-V029 migration,
legacy approval table or HTTP route is changed. Focused source tests cover exact
binding, task drift, revocation/terminal suppression, checkpointed unknown
outcomes and Java 8 consumption. Real database crash recovery remains an
external acceptance requirement for the persistence adapter.
