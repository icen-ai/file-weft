package ai.icen.fw.workflow.notification.persistence.jdbc

/**
 * Flyway locations owned by this artifact. Hosts add exactly one matching location to the
 * Workflow V030+ migration runner; this artifact deliberately never migrates on class loading.
 */
object WorkflowNotificationJdbcMigrations {
    const val VERSION: String = "035"

    @JvmStatic
    fun location(dialect: WorkflowNotificationJdbcDialect): String = when (dialect) {
        WorkflowNotificationJdbcDialect.POSTGRESQL ->
            "classpath:ai/icen/fw/workflow/notification/db/migration/postgres"
        WorkflowNotificationJdbcDialect.MYSQL ->
            "classpath:ai/icen/fw/workflow/notification/db/migration/mysql"
        WorkflowNotificationJdbcDialect.KINGBASE ->
            "classpath:ai/icen/fw/workflow/notification/db/migration/kingbase"
    }
}
