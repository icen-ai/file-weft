package ai.icen.fw.starter.boot3

import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.catalog.DocumentCatalogFolder
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.connector.ConnectorHealth
import ai.icen.fw.spi.connector.ConnectorHealthStatus
import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.spi.connector.ConnectorSyncResult
import ai.icen.fw.spi.connector.ConnectorSyncStatus
import ai.icen.fw.spi.connector.FileConnector
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.PrintWriter
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLFeatureNotSupportedException
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the bean naming contract restored after 0.0.3 renamed the Boot 3
 * runtime beans to short names: every bean exposed by the five split runtime
 * configurations is registered under the same `fileWeft*` primary name as the
 * Boot 2 starter, while the 0.0.3 short name stays available as a deprecated
 * alias resolving to the same instance.
 */
class FileWeftBeanNameCompatibilityTest {
    @TempDir
    lateinit var storageRoot: Path

    @Test
    fun `primary bean names match the Boot 2 starter surface`() {
        val declared = splitConfigurations().flatMap { type -> beanNamesOf(type) }
        assertEquals(EXPECTED_BOOT2_PRIMARY_NAMES, declared.map { names -> names.first() }.toSortedSet())
    }

    @Test
    fun `legacy 0 0 3 short names stay declared as aliases`() {
        val declared = splitConfigurations().flatMap { type -> beanNamesOf(type) }
        assertEquals(
            EXPECTED_LEGACY_ALIASES,
            declared.mapNotNull { names -> names.drop(1).firstOrNull() }.toSortedSet(),
        )
    }

    @Test
    fun `primary names and legacy aliases resolve to the same instances in the default runtime`() {
        contextRunner().withUserConfiguration(DatabaseConfiguration::class.java).run { context ->
            assertNull(context.startupFailure)
            assertAliasPairs(context, DEFAULT_RUNTIME_PAIRS)
        }
    }

    @Test
    fun `primary names and legacy aliases resolve to the same instances for catalog aware beans`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java, CatalogConfiguration::class.java)
            .run { context ->
                assertNull(context.startupFailure)
                assertAliasPairs(context, CATALOG_PAIRS)
            }
    }

    @Test
    fun `primary names and legacy aliases resolve to the same instances for legacy agent beans`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues("fileweft.compatibility.legacy-agent-autoconfiguration-enabled=true")
            .run { context ->
                assertNull(context.startupFailure)
                assertAliasPairs(context, LEGACY_AGENT_PAIRS)
            }
    }

    @Test
    fun `primary names and legacy aliases resolve to the same instances for legacy sync beans`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java, ConnectorConfiguration::class.java)
            .withPropertyValues(
                "fileweft.sync.legacy-publish-handler-enabled=true",
                "fileweft.sync.legacy-delivery-retry-enabled=true",
            )
            .run { context ->
                assertNull(context.startupFailure)
                assertAliasPairs(context, LEGACY_SYNC_PAIRS)
            }
    }

    @Test
    fun `name based conditions recognize the legacy aliases`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    FileWeftAutoConfiguration::class.java,
                    AliasConditionProbeAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues(
                "fileweft.default-tenant-enabled=true",
                "fileweft.default-tenant-id=test-tenant",
                "fileweft.storage.local-enabled=true",
                "fileweft.storage.local-root=${storageRoot.toAbsolutePath()}",
            )
            .run { context ->
                assertNull(context.startupFailure)
                assertFalse(context.containsBean("probeBackedOffByAlias"), "transaction alias must satisfy @ConditionalOnMissingBean(name)")
                assertTrue(context.containsBean("probePresentByAlias"), "documents alias must satisfy @ConditionalOnBean(name)")
                assertTrue(context.containsBean("probeAbsentName"))
            }
    }

    @Test
    fun `a host bean named after a 0 0 3 short name wins without a name clash`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java, HostShortNameTransactionConfiguration::class.java)
            .run { context ->
                assertNull(context.startupFailure)
                assertEquals(1, context.getBeansOfType(ApplicationTransaction::class.java).size)
                assertSame(DirectTransaction, context.getBean("transaction"))
                assertSame(context.getBean("transaction"), context.getBean(ApplicationTransaction::class.java))
            }
    }

    private fun contextRunner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FileWeftAutoConfiguration::class.java))
        .withPropertyValues(
            "fileweft.default-tenant-enabled=true",
            "fileweft.default-tenant-id=test-tenant",
            "fileweft.storage.local-enabled=true",
            "fileweft.storage.local-root=${storageRoot.toAbsolutePath()}",
        )

    private fun assertAliasPairs(context: AssertableApplicationContext, pairs: List<Pair<String, String>>) {
        pairs.forEach { (primary, alias) ->
            assertTrue(context.containsBean(primary), primary)
            assertTrue(context.containsBean(alias), alias)
            assertSame(context.getBean(primary), context.getBean(alias), "$primary must share its instance with $alias")
        }
    }

    private fun splitConfigurations(): List<Class<*>> = listOf(
        FileWeftDocumentConfiguration::class.java,
        FileWeftUploadConfiguration::class.java,
        FileWeftWorkflowConfiguration::class.java,
        FileWeftDeliveryConfiguration::class.java,
        FileWeftDoctorConfiguration::class.java,
    )

    private fun beanNamesOf(type: Class<*>): List<List<String>> =
        type.declaredMethods.mapNotNull { method ->
            val bean = method.getAnnotation(Bean::class.java) ?: return@mapNotNull null
            val explicit = (bean.value.toList() + bean.name.toList()).distinct()
            if (explicit.isEmpty()) listOf(method.name) else explicit
        }

    @Configuration(proxyBeanMethods = false)
    class DatabaseConfiguration {
        @Bean
        fun dataSource(): DataSource = StubDataSource
    }

    @Configuration(proxyBeanMethods = false)
    class CatalogConfiguration {
        @Bean
        fun hostCatalogProvider(): DocumentCatalogProvider = object : DocumentCatalogProvider {
            override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> =
                listOf(DocumentCatalogFolder("inbox", null, "Inbox"))
        }
    }

    @Configuration(proxyBeanMethods = false)
    class ConnectorConfiguration {
        @Bean
        fun fileConnector(): FileConnector = object : FileConnector {
            override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult =
                ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)

            override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult =
                ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)

            override fun health(): ConnectorHealth = ConnectorHealth(ConnectorHealthStatus.HEALTHY)
        }
    }

    /** Mimics a host that adapted to the 0.0.3 short bean names with its own transaction boundary. */
    @Configuration(proxyBeanMethods = false)
    class HostShortNameTransactionConfiguration {
        @Bean
        fun transaction(): ApplicationTransaction = DirectTransaction
    }

    /**
     * Ordered after the FileWeft auto-configuration so name based conditions see
     * the fully registered runtime bean surface, including aliases.
     */
    @AutoConfiguration(after = [FileWeftAutoConfiguration::class])
    @ConditionalOnBean(DataSource::class)
    class AliasConditionProbeAutoConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = ["transaction"])
        fun probeBackedOffByAlias(): String = "unexpected"

        @Bean
        @ConditionalOnBean(name = ["documents"])
        fun probePresentByAlias(): String = "alias-visible"

        @Bean
        @ConditionalOnMissingBean(name = ["fileWeftBeanThatDoesNotExist"])
        fun probeAbsentName(): String = "absent"
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private object StubDataSource : DataSource {
        override fun getConnection(): Connection = throw UnsupportedOperationException("Test data source")
        override fun getConnection(username: String?, password: String?): Connection = throw UnsupportedOperationException("Test data source")
        override fun getLogWriter(): PrintWriter? = null
        override fun setLogWriter(out: PrintWriter?) = Unit
        override fun setLoginTimeout(seconds: Int) = Unit
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = Logger.getGlobal()
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLFeatureNotSupportedException()
        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }

    private companion object {
        val EXPECTED_BOOT2_PRIMARY_NAMES: Set<String> = sortedSetOf(
            "fileWeftTransaction",
            "fileWeftDocumentRepository",
            "fileWeftDocumentQueryRepository",
            "fileWeftDocumentAuditLogQueryRepository",
            "fileWeftFileObjectRepository",
            "fileWeftFileAssetRepository",
            "fileWeftAuditRepository",
            "fileWeftOperationLogRepository",
            "fileWeftOutboxEventRepository",
            "fileWeftOutboxProcessingRepository",
            "fileWeftOutboxBacklogReader",
            "fileWeftOutboxBacklogMetricsPublisher",
            "fileWeftTaskRepository",
            "fileWeftRequestIdempotencyRepository",
            "fileWeftRequestIdempotencyService",
            "fileWeftMetadataSchemaRegistry",
            "fileWeftMetadataProcessor",
            "fileWeftMetadataSchemaQueryService",
            "fileWeftDocumentMetadataService",
            "fileWeftDocumentMetadataWriteService",
            "fileWeftDocumentCatalogAccessService",
            "fileWeftDocumentCatalogBindingService",
            "fileWeftDocumentCatalogDraftService",
            "fileWeftDocumentCatalogMutationService",
            "fileWeftDocumentCatalogLifecycleService",
            "fileWeftIdempotentDocumentLifecycleService",
            "fileWeftIdempotentDocumentCatalogLifecycleService",
            "fileWeftAuditTrail",
            "fileWeftDocumentQueryService",
            "fileWeftDocumentAuditLogQueryService",
            "fileWeftDocumentCommandService",
            "fileWeftDocumentDownloadVisibility",
            "fileWeftDocumentDownloadService",
            "fileWeftDocumentDraftService",
            "fileWeftPublishService",
            "fileWeftOfflineService",
            "fileWeftRestoreOfflineDocumentService",
            "fileWeftArchiveService",
            "fileWeftTaskWorker",
            "fileWeftResumableUploadSessionRepository",
            "fileWeftUploadService",
            "fileWeftResumableUploadService",
            "fileWeftWorkflowQueryRepository",
            "fileWeftWorkflowDecisionEvidenceQueryRepository",
            "fileWeftWorkflowRepository",
            "fileWeftIdempotentDocumentReviewWorkflowService",
            "fileWeftIdempotentDocumentCatalogReviewWorkflowService",
            "fileWeftWorkflowQueryService",
            "fileWeftWorkflowDecisionEvidenceQueryService",
            "fileWeftDefaultDocumentReviewRouteProvider",
            "fileWeftDocumentReviewRouteResolver",
            "fileWeftReviewWorkflowService",
            "fileWeftDocumentSyncStatusQueryRepository",
            "fileWeftSyncRecordRepository",
            "fileWeftDocumentDeliveryTargetRepository",
            "fileWeftIdempotentDocumentDeliveryRecoveryService",
            "fileWeftIdempotentDocumentCatalogDeliveryRecoveryService",
            "fileWeftConnectorInvocationExecutor",
            "fileWeftConnectorResiliencePolicy",
            "fileWeftConnectorResilienceRegistry",
            "fileWeftDeliveryConnectorResolver",
            "fileWeftDocumentDeliveryProfiles",
            "fileWeftDocumentDeliveryPlanner",
            "fileWeftDocumentDeliveryRemovalPlanner",
            "fileWeftDocumentDeliverySyncService",
            "fileWeftDocumentDeliveryRemovalService",
            "fileWeftDocumentDeliveryOutboxEventHandler",
            "fileWeftRetryDocumentDeliveryService",
            "fileWeftDocumentSyncStatusQueryService",
            "fileWeftDocumentSyncService",
            "fileWeftDocumentPublishOutboxEventHandler",
            "fileWeftOutboxWorker",
            "documentDoctorTaskQueries",
            "documentDoctorQueryService",
            "documentDoctorTaskQueryService",
            "systemDoctorService",
            "idempotentScheduleDocumentDoctorService",
            "idempotentScheduleDocumentCatalogDoctorService",
            "fileWeftDoctorReportRepository",
            "fileWeftAgentResultRepository",
            "fileWeftConfirmAgentSuggestionService",
            "fileWeftAgentTaskOrchestrator",
            "fileWeftAgentDoctorChecker",
            "fileWeftAgentTaskScheduler",
            "fileWeftAgentTaskHandler",
            "fileWeftAgentTaskOutboxEventHandler",
            "fileWeftAgentSuggestionConfirmations",
            "fileWeftDeploymentSafetyDoctorChecker",
            "fileWeftPermissionDoctorChecker",
            "fileWeftLifecycleDoctorChecker",
            "fileWeftStorageDoctorChecker",
            "fileWeftWorkflowDoctorChecker",
            "fileWeftMetadataDoctorChecker",
            "fileWeftCatalogDoctorChecker",
            "fileWeftConnectorDoctorChecker",
            "fileWeftDeliveryProfileDoctorChecker",
            "fileWeftDocumentDoctorTaskHandler",
            "fileWeftScheduleDocumentDoctorService",
            "fileWeftDoctorService",
        )

        val EXPECTED_LEGACY_ALIASES: Set<String> = sortedSetOf(
            "transaction",
            "documents",
            "documentQueries",
            "documentAuditLogQueries",
            "fileObjects",
            "fileAssets",
            "audits",
            "operationLogs",
            "outboxEvents",
            "outboxProcessing",
            "outboxBacklogReader",
            "outboxBacklogMetricsPublisher",
            "tasks",
            "requestIdempotencyRepository",
            "requestIdempotencyService",
            "metadataSchemaRegistry",
            "metadataProcessor",
            "metadataSchemaQueryService",
            "documentMetadataService",
            "documentMetadataWriteService",
            "documentCatalogAccessService",
            "documentCatalogBindingService",
            "documentCatalogDraftService",
            "documentCatalogMutationService",
            "documentCatalogLifecycleService",
            "idempotentDocumentLifecycleService",
            "idempotentDocumentCatalogLifecycleService",
            "auditTrail",
            "documentQueryService",
            "documentAuditLogQueryService",
            "documentCommands",
            "documentDownloadVisibility",
            "documentDownloads",
            "documentDraftService",
            "publishService",
            "offlineService",
            "restoreOfflineService",
            "archiveService",
            "taskWorker",
            "resumableUploadSessions",
            "uploadService",
            "resumableUploadService",
            "workflowQueries",
            "workflowDecisionEvidenceQueries",
            "workflows",
            "idempotentDocumentReviewWorkflowService",
            "idempotentDocumentCatalogReviewWorkflowService",
            "workflowQueryService",
            "workflowDecisionEvidenceQueryService",
            "defaultDocumentReviewRouteProvider",
            "documentReviewRouteResolver",
            "reviewWorkflowService",
            "documentSyncStatusQueries",
            "syncRecords",
            "documentDeliveryTargets",
            "idempotentDocumentDeliveryRecoveryService",
            "idempotentDocumentCatalogDeliveryRecoveryService",
            "connectorInvocationExecutor",
            "connectorResiliencePolicy",
            "connectorResilienceRegistry",
            "deliveryConnectorResolver",
            "documentDeliveryProfiles",
            "documentDeliveryPlanner",
            "documentDeliveryRemovalPlanner",
            "documentDeliverySyncService",
            "documentDeliveryRemovalService",
            "documentDeliveryHandler",
            "retryDocumentDeliveryService",
            "documentSyncStatusQueryService",
            "documentSyncService",
            "documentPublishHandler",
            "outboxWorker",
            "doctorReports",
            "agentResults",
            "confirmAgentSuggestionService",
            "agentTaskOrchestrator",
            "agentDoctorChecker",
            "agentTaskScheduler",
            "agentTaskHandler",
            "agentTaskOutboxEventHandler",
            "agentSuggestionConfirmations",
            "deploymentSafetyDoctor",
            "permissionDoctor",
            "lifecycleDoctor",
            "storageDoctor",
            "workflowDoctor",
            "metadataDoctor",
            "catalogDoctor",
            "connectorDoctor",
            "deliveryProfileDoctor",
            "documentDoctorTaskHandler",
            "scheduleDocumentDoctorService",
            "doctorService",
        )

        val DEFAULT_RUNTIME_PAIRS: List<Pair<String, String>> = listOf(
            "fileWeftTransaction" to "transaction",
            "fileWeftDocumentRepository" to "documents",
            "fileWeftDocumentQueryRepository" to "documentQueries",
            "fileWeftDocumentAuditLogQueryRepository" to "documentAuditLogQueries",
            "fileWeftFileObjectRepository" to "fileObjects",
            "fileWeftFileAssetRepository" to "fileAssets",
            "fileWeftAuditRepository" to "audits",
            "fileWeftOperationLogRepository" to "operationLogs",
            "fileWeftOutboxEventRepository" to "outboxEvents",
            "fileWeftOutboxProcessingRepository" to "outboxProcessing",
            "fileWeftOutboxBacklogReader" to "outboxBacklogReader",
            "fileWeftOutboxBacklogMetricsPublisher" to "outboxBacklogMetricsPublisher",
            "fileWeftTaskRepository" to "tasks",
            "fileWeftRequestIdempotencyRepository" to "requestIdempotencyRepository",
            "fileWeftRequestIdempotencyService" to "requestIdempotencyService",
            "fileWeftMetadataSchemaRegistry" to "metadataSchemaRegistry",
            "fileWeftMetadataProcessor" to "metadataProcessor",
            "fileWeftMetadataSchemaQueryService" to "metadataSchemaQueryService",
            "fileWeftDocumentMetadataService" to "documentMetadataService",
            "fileWeftDocumentMetadataWriteService" to "documentMetadataWriteService",
            "fileWeftIdempotentDocumentLifecycleService" to "idempotentDocumentLifecycleService",
            "fileWeftAuditTrail" to "auditTrail",
            "fileWeftDocumentQueryService" to "documentQueryService",
            "fileWeftDocumentAuditLogQueryService" to "documentAuditLogQueryService",
            "fileWeftDocumentCommandService" to "documentCommands",
            "fileWeftDocumentDownloadService" to "documentDownloads",
            "fileWeftDocumentDraftService" to "documentDraftService",
            "fileWeftPublishService" to "publishService",
            "fileWeftOfflineService" to "offlineService",
            "fileWeftRestoreOfflineDocumentService" to "restoreOfflineService",
            "fileWeftArchiveService" to "archiveService",
            "fileWeftTaskWorker" to "taskWorker",
            "fileWeftResumableUploadSessionRepository" to "resumableUploadSessions",
            "fileWeftUploadService" to "uploadService",
            "fileWeftResumableUploadService" to "resumableUploadService",
            "fileWeftWorkflowQueryRepository" to "workflowQueries",
            "fileWeftWorkflowDecisionEvidenceQueryRepository" to "workflowDecisionEvidenceQueries",
            "fileWeftWorkflowRepository" to "workflows",
            "fileWeftIdempotentDocumentReviewWorkflowService" to "idempotentDocumentReviewWorkflowService",
            "fileWeftWorkflowQueryService" to "workflowQueryService",
            "fileWeftWorkflowDecisionEvidenceQueryService" to "workflowDecisionEvidenceQueryService",
            "fileWeftDefaultDocumentReviewRouteProvider" to "defaultDocumentReviewRouteProvider",
            "fileWeftDocumentReviewRouteResolver" to "documentReviewRouteResolver",
            "fileWeftReviewWorkflowService" to "reviewWorkflowService",
            "fileWeftDocumentSyncStatusQueryRepository" to "documentSyncStatusQueries",
            "fileWeftSyncRecordRepository" to "syncRecords",
            "fileWeftDocumentDeliveryTargetRepository" to "documentDeliveryTargets",
            "fileWeftIdempotentDocumentDeliveryRecoveryService" to "idempotentDocumentDeliveryRecoveryService",
            "fileWeftConnectorInvocationExecutor" to "connectorInvocationExecutor",
            "fileWeftConnectorResiliencePolicy" to "connectorResiliencePolicy",
            "fileWeftConnectorResilienceRegistry" to "connectorResilienceRegistry",
            "fileWeftDeliveryConnectorResolver" to "deliveryConnectorResolver",
            "fileWeftDocumentDeliveryProfiles" to "documentDeliveryProfiles",
            "fileWeftDocumentDeliveryPlanner" to "documentDeliveryPlanner",
            "fileWeftDocumentDeliveryRemovalPlanner" to "documentDeliveryRemovalPlanner",
            "fileWeftDocumentDeliverySyncService" to "documentDeliverySyncService",
            "fileWeftDocumentDeliveryRemovalService" to "documentDeliveryRemovalService",
            "fileWeftDocumentDeliveryOutboxEventHandler" to "documentDeliveryHandler",
            "fileWeftDocumentSyncStatusQueryService" to "documentSyncStatusQueryService",
            "fileWeftOutboxWorker" to "outboxWorker",
            "fileWeftDoctorReportRepository" to "doctorReports",
            "fileWeftDeploymentSafetyDoctorChecker" to "deploymentSafetyDoctor",
            "fileWeftPermissionDoctorChecker" to "permissionDoctor",
            "fileWeftLifecycleDoctorChecker" to "lifecycleDoctor",
            "fileWeftStorageDoctorChecker" to "storageDoctor",
            "fileWeftWorkflowDoctorChecker" to "workflowDoctor",
            "fileWeftMetadataDoctorChecker" to "metadataDoctor",
            "fileWeftConnectorDoctorChecker" to "connectorDoctor",
            "fileWeftDeliveryProfileDoctorChecker" to "deliveryProfileDoctor",
            "fileWeftDocumentDoctorTaskHandler" to "documentDoctorTaskHandler",
            "fileWeftScheduleDocumentDoctorService" to "scheduleDocumentDoctorService",
            "fileWeftDoctorService" to "doctorService",
        )

        val CATALOG_PAIRS: List<Pair<String, String>> = listOf(
            "fileWeftDocumentCatalogAccessService" to "documentCatalogAccessService",
            "fileWeftDocumentCatalogBindingService" to "documentCatalogBindingService",
            "fileWeftDocumentCatalogDraftService" to "documentCatalogDraftService",
            "fileWeftDocumentCatalogMutationService" to "documentCatalogMutationService",
            "fileWeftDocumentCatalogLifecycleService" to "documentCatalogLifecycleService",
            "fileWeftIdempotentDocumentCatalogLifecycleService" to "idempotentDocumentCatalogLifecycleService",
            "fileWeftIdempotentDocumentCatalogReviewWorkflowService" to "idempotentDocumentCatalogReviewWorkflowService",
            "fileWeftIdempotentDocumentCatalogDeliveryRecoveryService" to "idempotentDocumentCatalogDeliveryRecoveryService",
            "fileWeftCatalogDoctorChecker" to "catalogDoctor",
            "fileWeftDocumentDownloadVisibility" to "documentDownloadVisibility",
        )

        val LEGACY_AGENT_PAIRS: List<Pair<String, String>> = listOf(
            "fileWeftAgentResultRepository" to "agentResults",
            "fileWeftConfirmAgentSuggestionService" to "confirmAgentSuggestionService",
            "fileWeftAgentTaskOrchestrator" to "agentTaskOrchestrator",
            "fileWeftAgentDoctorChecker" to "agentDoctorChecker",
            "fileWeftAgentTaskScheduler" to "agentTaskScheduler",
            "fileWeftAgentTaskHandler" to "agentTaskHandler",
            "fileWeftAgentTaskOutboxEventHandler" to "agentTaskOutboxEventHandler",
            "fileWeftAgentSuggestionConfirmations" to "agentSuggestionConfirmations",
            "fileWeftDoctorService" to "doctorService",
        )

        val LEGACY_SYNC_PAIRS: List<Pair<String, String>> = listOf(
            "fileWeftDocumentSyncService" to "documentSyncService",
            "fileWeftDocumentPublishOutboxEventHandler" to "documentPublishHandler",
            "fileWeftRetryDocumentDeliveryService" to "retryDocumentDeliveryService",
        )
    }
}
