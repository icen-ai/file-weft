package ai.icen.fw.workflow.cycle.guard.persistence.jdbc

/** Supported production schema contracts. Resources are intentionally not shared V0xx migrations. */
enum class WorkflowCycleGuardJdbcSchemaDialect(val resourcePath: String) {
    POSTGRESQL("/ai/icen/fw/workflow/cycle/guard/persistence/schema/postgres.sql"),
    MYSQL("/ai/icen/fw/workflow/cycle/guard/persistence/schema/mysql.sql"),
    KINGBASE("/ai/icen/fw/workflow/cycle/guard/persistence/schema/kingbase.sql"),
}

class WorkflowCycleGuardJdbcSchema private constructor() {
    companion object {
        @JvmStatic
        fun resourcePath(dialect: WorkflowCycleGuardJdbcSchemaDialect): String = dialect.resourcePath
    }
}
