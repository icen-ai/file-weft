package ai.icen.fw.starter.boot2

import ai.icen.fw.adapter.authorization.DefaultAuthorizationProvider
import ai.icen.fw.adapter.connector.ConnectorInvocationExecutor
import ai.icen.fw.adapter.connector.ConnectorResiliencePolicy
import ai.icen.fw.adapter.connector.ConnectorResilienceRegistry
import ai.icen.fw.agent.AgentTaskHandler
import ai.icen.fw.agent.AgentDoctorChecker
import ai.icen.fw.agent.AgentTaskOrchestrator
import ai.icen.fw.agent.AgentTaskOutboxEventHandler
import ai.icen.fw.agent.AgentTaskScheduler
import ai.icen.fw.agent.PersistedAgentSuggestionConfirmationService
import ai.icen.fw.application.agent.AgentResultRepository
import ai.icen.fw.application.agent.ConfirmAgentSuggestionService
import ai.icen.fw.adapter.identity.DefaultUserRealmProvider
import ai.icen.fw.adapter.storage.LocalStorageAdapter
import ai.icen.fw.adapter.tenant.FixedTenantProvider
import ai.icen.fw.adapter.observability.NoOpFileWeftMetrics
import ai.icen.fw.adapter.micrometer.MicrometerFileWeftGauges
import ai.icen.fw.adapter.micrometer.MicrometerFileWeftMetrics
import ai.icen.fw.adapter.observability.NoOpTraceContextProvider
import ai.icen.fw.application.archive.ArchiveDocumentService
import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.catalog.DocumentCatalogBindingService
import ai.icen.fw.application.catalog.DocumentCatalogDraftService
import ai.icen.fw.application.catalog.DocumentCatalogLifecycleService
import ai.icen.fw.application.catalog.DocumentCatalogMutationService
import ai.icen.fw.application.doctor.CatalogDoctorChecker
import ai.icen.fw.application.doctor.DeliveryProfileDoctorChecker
import ai.icen.fw.application.doctor.DeploymentSafetyDoctorChecker
import ai.icen.fw.application.doctor.DoctorApplicationService
import ai.icen.fw.application.doctor.DocumentDoctorTaskHandler
import ai.icen.fw.application.doctor.DocumentDoctorQueryService
import ai.icen.fw.application.doctor.DocumentDoctorTaskQueryRepository
import ai.icen.fw.application.doctor.DocumentDoctorTaskQueryService
import ai.icen.fw.application.doctor.IdempotentScheduleDocumentCatalogDoctorService
import ai.icen.fw.application.doctor.IdempotentScheduleDocumentDoctorService
import ai.icen.fw.application.doctor.MetadataDoctorChecker
import ai.icen.fw.application.doctor.SystemDoctorService
import ai.icen.fw.application.doctor.WorkflowDoctorChecker
import ai.icen.fw.application.delivery.DocumentDeliverySyncService
import ai.icen.fw.application.delivery.DocumentDeliveryTarget
import ai.icen.fw.application.delivery.DocumentDeliveryTargetRepository
import ai.icen.fw.application.document.DocumentDraftService
import ai.icen.fw.application.document.DocumentCommandService
import ai.icen.fw.application.document.DocumentDownloadService
import ai.icen.fw.application.document.DocumentDownloadVisibility
import ai.icen.fw.application.document.DocumentFolderReadAccess
import ai.icen.fw.application.document.DocumentPageRequest
import ai.icen.fw.application.document.DocumentPageResult
import ai.icen.fw.application.document.DocumentQueryRepository
import ai.icen.fw.application.document.DocumentQueryService
import ai.icen.fw.application.idempotency.IdempotencyResult
import ai.icen.fw.application.idempotency.RequestIdempotency
import ai.icen.fw.application.idempotency.RequestIdempotencyClaim
import ai.icen.fw.application.idempotency.RequestIdempotencyRecord
import ai.icen.fw.application.idempotency.RequestIdempotencyRepository
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.lifecycle.IdempotentDocumentCatalogLifecycleService
import ai.icen.fw.application.lifecycle.IdempotentDocumentLifecycleService
import ai.icen.fw.application.metadata.DocumentMetadataService
import ai.icen.fw.application.metadata.DocumentMetadataWriteService
import ai.icen.fw.application.metadata.MetadataSchemaQueryService
import ai.icen.fw.application.upload.ResumableUploadService
import ai.icen.fw.application.upload.ResumableUploadSessionRepository
import ai.icen.fw.application.outbox.OutboxWorker
import ai.icen.fw.application.outbox.OutboxBacklogMetricsPublisher
import ai.icen.fw.application.outbox.OutboxBacklogReader
import ai.icen.fw.application.offline.OfflineDocumentService
import ai.icen.fw.application.offline.RestoreOfflineDocumentService
import ai.icen.fw.application.publish.PublishDocumentService
import ai.icen.fw.application.sync.DocumentPublishOutboxEventHandler
import ai.icen.fw.application.sync.DocumentSyncService
import ai.icen.fw.application.task.BackgroundTask
import ai.icen.fw.application.task.BackgroundTaskLease
import ai.icen.fw.application.task.TaskMutationRepository
import ai.icen.fw.application.task.TaskProcessingRepository
import ai.icen.fw.application.task.TaskRepository
import ai.icen.fw.application.task.TaskWorker
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.upload.UploadApplicationService
import ai.icen.fw.persistence.jdbc.JdbcOutboxBacklogReader
import ai.icen.fw.persistence.jdbc.JdbcDocumentQueryRepository
import ai.icen.fw.persistence.jdbc.JdbcDocumentDoctorTaskQueryRepository
import ai.icen.fw.persistence.jdbc.JdbcFileAssetRepository
import ai.icen.fw.persistence.jdbc.JdbcRequestIdempotencyRepository
import ai.icen.fw.persistence.jdbc.JdbcWorkflowQueryRepository
import ai.icen.fw.persistence.jdbc.JdbcWorkflowDecisionEvidenceQueryRepository
import ai.icen.fw.application.workflow.DocumentReviewRouteResolver
import ai.icen.fw.application.workflow.DocumentReviewWorkflowService
import ai.icen.fw.application.workflow.DocumentWorkflowPageRequest
import ai.icen.fw.application.workflow.DocumentWorkflowPageResult
import ai.icen.fw.application.workflow.IdempotentDocumentCatalogReviewWorkflowService
import ai.icen.fw.application.workflow.IdempotentDocumentReviewWorkflowService
import ai.icen.fw.application.workflow.WorkflowQueryRepository
import ai.icen.fw.application.workflow.WorkflowQueryService
import ai.icen.fw.application.workflow.WorkflowDecisionEvidenceQueryRepository
import ai.icen.fw.application.workflow.WorkflowDecisionEvidenceQueryService
import ai.icen.fw.application.workflow.WorkflowTaskPageRequest
import ai.icen.fw.application.workflow.WorkflowTaskPageResult
import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.runtime.plugin.FileWeftPluginRegistry
import ai.icen.fw.spi.plugin.FileWeftPlugin
import ai.icen.fw.spi.catalog.DocumentCatalogFolder
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.observability.FileWeftGaugeRecorder
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.spi.observability.TraceContextScope
import ai.icen.fw.domain.operation.OperationLogRepository
import ai.icen.fw.domain.document.DocumentMutationRepository
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetMutationRepository
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.workflow.WorkflowInstanceRepository
import ai.icen.fw.metadata.api.MetadataField
import ai.icen.fw.metadata.api.MetadataFieldType
import ai.icen.fw.metadata.api.MetadataProcessor
import ai.icen.fw.metadata.api.MetadataSchema
import ai.icen.fw.metadata.api.MetadataSchemaContext
import ai.icen.fw.metadata.api.MetadataSchemaResolver
import ai.icen.fw.metadata.runtime.HistoricalMetadataSchema
import ai.icen.fw.metadata.runtime.MetadataSchemaRegistry
import ai.icen.fw.spi.authorization.AuthorizationAction
import ai.icen.fw.spi.authorization.AuthorizationEnvironment
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.authorization.AuthorizationResource
import ai.icen.fw.spi.authorization.AuthorizationSubject
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.connector.ConnectorHealth
import ai.icen.fw.spi.connector.ConnectorHealthStatus
import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.spi.connector.ConnectorSyncResult
import ai.icen.fw.spi.connector.ConnectorSyncStatus
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.delivery.DocumentDeliveryProfileProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageDownload
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import ai.icen.fw.spi.workflow.DocumentReviewRoute
import ai.icen.fw.spi.workflow.DocumentReviewRouteProvider
import ai.icen.fw.spi.workflow.DocumentReviewRouteRequest
import ai.icen.fw.spi.workflow.DocumentReviewRouteTask
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLFeatureNotSupportedException
import java.util.logging.Logger
import javax.sql.DataSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileWeftAutoConfigurationTest {
    @TempDir
    lateinit var storageRoot: Path

    @Test
    fun `fails startup clearly when no trusted tenant provider is configured`() {
        strictContextRunner()
            .withPropertyValues(
                "fileweft.storage.local-enabled=true",
                "fileweft.storage.local-root=${storageRoot.toAbsolutePath()}",
            )
            .run { context ->
                assertTrue(context.startupFailure.causeMessages().any { message ->
                    message.contains("No TenantProvider is configured") &&
                        message.contains("fileweft.default-tenant-enabled=true")
                })
            }
    }

    @Test
    fun `fails startup clearly when no durable storage adapter is configured`() {
        strictContextRunner()
            .withUserConfiguration(TenantOnlyConfiguration::class.java)
            .run { context ->
                assertTrue(context.startupFailure.causeMessages().any { message ->
                    message.contains("No StorageAdapter is configured") &&
                        message.contains("fileweft.storage.local-enabled=true")
                })
            }
    }

    @Test
    fun `legacy fixed tenant id alone does not opt into the fallback`() {
        strictContextRunner()
            .withUserConfiguration(StorageOnlyConfiguration::class.java)
            .withPropertyValues("fileweft.default-tenant-id=legacy-tenant")
            .run { context ->
                assertTrue(context.startupFailure.causeMessages().any { message ->
                    message.contains("No TenantProvider is configured")
                })
            }
    }

    @Test
    fun `legacy local root alone does not opt in or create the directory`() {
        val inactiveRoot = storageRoot.resolve("legacy-local-root")
        assertFalse(Files.exists(inactiveRoot))

        strictContextRunner()
            .withUserConfiguration(TenantOnlyConfiguration::class.java)
            .withPropertyValues("fileweft.storage.local-root=${inactiveRoot.toAbsolutePath()}")
            .run { context ->
                assertTrue(context.startupFailure.causeMessages().any { message ->
                    message.contains("No StorageAdapter is configured")
                })
            }

        assertFalse(Files.exists(inactiveRoot))
    }

    @Test
    fun `creates fixed tenant and local storage only through complete explicit opt in`() {
        val enabledRoot = storageRoot.resolve("explicit-local-root")
        assertFalse(Files.exists(enabledRoot))

        strictContextRunner()
            .withPropertyValues(
                "fileweft.default-tenant-enabled=true",
                "fileweft.default-tenant-id=tenant-a",
                "fileweft.storage.local-enabled=true",
                "fileweft.storage.local-root=${enabledRoot.toAbsolutePath()}",
            )
            .run { context ->
                assertNull(context.startupFailure)
                assertTrue(context.getBean(TenantProvider::class.java) is FixedTenantProvider)
                assertEquals("tenant-a", context.getBean(TenantProvider::class.java).currentTenant().tenantId.value)
                assertTrue(context.getBean(StorageAdapter::class.java) is LocalStorageAdapter)
                assertTrue(Files.isDirectory(enabledRoot))
            }
    }

    @Test
    fun `rejects blank fixed tenant id during startup`() {
        strictContextRunner()
            .withPropertyValues(
                "fileweft.default-tenant-enabled=true",
                "fileweft.default-tenant-id=   ",
                "fileweft.storage.local-enabled=true",
                "fileweft.storage.local-root=${storageRoot.toAbsolutePath()}",
            )
            .run { context ->
                assertTrue(context.startupFailure.causeMessages().any { message ->
                    message.contains("fileweft.default-tenant-id must not be blank")
                })
            }
    }

    @Test
    fun `rejects blank local storage root during startup`() {
        strictContextRunner()
            .withUserConfiguration(TenantOnlyConfiguration::class.java)
            .withPropertyValues(
                "fileweft.storage.local-enabled=true",
                "fileweft.storage.local-root=   ",
            )
            .run { context ->
                assertTrue(context.startupFailure.causeMessages().any { message ->
                    message.contains("fileweft.storage.local-root must not be blank")
                })
            }
    }

    @Test
    fun `publishes disabled by default metadata for explicit fallback settings`() {
        val resource = requireNotNull(
            javaClass.classLoader.getResourceAsStream("META-INF/spring-configuration-metadata.json"),
        )
        val metadata = resource.use { input -> ObjectMapper().readTree(input) }
        val properties = metadata.path("properties").toList()
        val expectedTypes = linkedMapOf(
            "fileweft.default-tenant-enabled" to "java.lang.Boolean",
            "fileweft.default-tenant-id" to "java.lang.String",
            "fileweft.storage.local-enabled" to "java.lang.Boolean",
            "fileweft.storage.local-root" to "java.lang.String",
            "fileweft.compatibility.legacy-agent-autoconfiguration-enabled" to "java.lang.Boolean",
        )
        val requiredDescriptionFragments = mapOf(
            "fileweft.default-tenant-enabled" to listOf("fixed single-tenant", "TenantProvider", "production multi-tenant"),
            "fileweft.default-tenant-id" to listOf("fileweft.default-tenant-enabled", "configured explicitly"),
            "fileweft.storage.local-enabled" to listOf("process-local filesystem", "StorageAdapter", "durable production storage"),
            "fileweft.storage.local-root" to listOf("fileweft.storage.local-enabled", "configured explicitly"),
            "fileweft.compatibility.legacy-agent-autoconfiguration-enabled" to
                listOf("compatibility-only", "legacy Agent", "disabled by default", "must not be used"),
        )

        expectedTypes.forEach { (name, type) ->
            val matches = properties.filter { property -> property.path("name").asText() == name }
            assertEquals(1, matches.size, "Expected exactly one generated metadata entry for $name")
            val property = matches.single()
            assertEquals(type, property.path("type").asText())
            requiredDescriptionFragments.getValue(name).forEach { fragment ->
                assertTrue(property.path("description").asText().contains(fragment, ignoreCase = true))
            }
        }

        listOf(
            "fileweft.default-tenant-enabled",
            "fileweft.storage.local-enabled",
            "fileweft.compatibility.legacy-agent-autoconfiguration-enabled",
        ).forEach { name ->
            val property = properties.single { candidate -> candidate.path("name").asText() == name }
            assertTrue(property.path("defaultValue").isBoolean)
            assertFalse(property.path("defaultValue").booleanValue())
        }
        listOf("fileweft.default-tenant-id", "fileweft.storage.local-root").forEach { name ->
            val property = properties.single { candidate -> candidate.path("name").asText() == name }
            assertFalse(property.has("defaultValue"))
        }
    }

    @Test
    fun `binds the default tenant property`() {
        contextRunner().withPropertyValues("fileweft.default-tenant-id=tenant-a").run { context ->
            assertEquals("tenant-a", context.getBean(TenantProvider::class.java).currentTenant().tenantId.value)
        }
    }

    @Test
    fun `binds the independent source URL lifetime into delivery runtime wiring`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues(
                "fileweft.sync.connector-timeout-millis=2000",
                "fileweft.sync.source-access-url-ttl-millis=9000",
            )
            .run { context ->
                assertEquals(9_000, context.getBean(FileWeftProperties::class.java).sync.sourceAccessUrlTtlMillis)
                val delivery = context.getBean(DocumentDeliverySyncService::class.java)
                val sourceUrlTtl = delivery.javaClass.getDeclaredField("sourceAccessUrlTtl")
                    .apply { isAccessible = true }
                    .get(delivery)
                assertEquals(Duration.ofSeconds(9), sourceUrlTtl)
            }
    }

    @Test
    fun `selects an explicitly configured default delivery profile`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues(*deliveryProfileProperties())
            .withPropertyValues("fileweft.sync.default-profile-id=second")
            .run { context ->
                assertNull(context.startupFailure)
                assertEquals(
                    "second",
                    context.getBean(DocumentDeliveryProfileProvider::class.java)
                        .defaultProfile(Identifier("test-tenant"))
                        ?.id,
                )
            }
    }

    @Test
    fun `preserves the first configured delivery profile when the default property is omitted`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues(*deliveryProfileProperties())
            .run { context ->
                assertNull(context.startupFailure)
                assertEquals(
                    "first",
                    context.getBean(DocumentDeliveryProfileProvider::class.java)
                        .defaultProfile(Identifier("test-tenant"))
                        ?.id,
                )
            }
    }

    @Test
    fun `fails startup when the configured default delivery profile does not exist`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues(*deliveryProfileProperties())
            .withPropertyValues("fileweft.sync.default-profile-id=seocnd")
            .run { context ->
                assertTrue(context.startupFailure.causeMessages().any { message ->
                    message.contains("fileweft.sync.default-profile-id 'seocnd'") &&
                        message.contains("Available profile ids: first, second")
                })
            }
    }

    @Test
    fun `keeps the unfenced legacy publish handler disabled by default`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java, ConnectorConfiguration::class.java)
            .run { context ->
                assertFalse(context.getBean(FileWeftProperties::class.java).sync.legacyPublishHandlerEnabled)
                assertTrue(context.getBeansOfType(DocumentSyncService::class.java).isEmpty())
                assertTrue(context.getBeansOfType(DocumentPublishOutboxEventHandler::class.java).isEmpty())
            }
    }

    @Test
    fun `fails startup clearly when a custom delivery repository lacks mutation fencing`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                LegacyDeliveryTargetRepositoryConfiguration::class.java,
            )
            .run { context ->
                val messages = generateSequence(context.startupFailure) { failure -> failure.cause }
                    .mapNotNull { failure -> failure.message }
                    .toList()
                assertTrue(messages.any { message ->
                    message.contains("must also implement DocumentDeliveryTargetMutationRepository")
                })
            }
    }

    @Test
    fun `fails startup clearly when a custom task repository lacks projection fencing`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                LegacyTaskRepositoryConfiguration::class.java,
            )
            .run { context ->
                val messages = generateSequence(context.startupFailure) { failure -> failure.cause }
                    .mapNotNull { failure -> failure.message }
                    .toList()
                assertTrue(messages.any { message ->
                    message.contains("Exactly one TaskMutationRepository is required for fenced task projections")
                })
            }
    }

    @Test
    fun `enables the legacy publish handler only through the compatibility property`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java, ConnectorConfiguration::class.java)
            .withPropertyValues("fileweft.sync.legacy-publish-handler-enabled=true")
            .run { context ->
                assertTrue(context.getBean(FileWeftProperties::class.java).sync.legacyPublishHandlerEnabled)
                assertTrue(context.getBean(DocumentSyncService::class.java) != null)
                assertTrue(context.getBean(DocumentPublishOutboxEventHandler::class.java) != null)
            }
    }

    @Test
    fun `does not replace customer extension beans`() {
        strictContextRunner()
            .withUserConfiguration(CustomerConfiguration::class.java)
            .withPropertyValues(
                "fileweft.default-tenant-enabled=true",
                "fileweft.default-tenant-id=",
                "fileweft.storage.local-enabled=true",
                "fileweft.storage.local-root=",
            )
            .run { context ->
                assertNull(context.startupFailure)
                assertSame(context.getBean("customerTenantProvider"), context.getBean(TenantProvider::class.java))
                assertSame(context.getBean("customerUserRealmProvider"), context.getBean(UserRealmProvider::class.java))
                assertSame(context.getBean("customerAuthorizationProvider"), context.getBean(AuthorizationProvider::class.java))
                assertSame(context.getBean("customerStorageAdapter"), context.getBean(StorageAdapter::class.java))
            }
    }

    @Test
    fun `binds persisted Outbox lease settings into the runtime worker`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues(
                "fileweft.outbox.worker-id=boot2-outbox-worker",
                "fileweft.outbox.lease-duration-millis=9000",
                "fileweft.outbox.legacy-running-grace-millis=4000",
            )
            .run { context ->
                val outbox = context.getBean(FileWeftProperties::class.java).outbox
                assertEquals("boot2-outbox-worker", outbox.workerId)
                assertEquals(9_000, outbox.leaseDurationMillis)
                assertEquals(4_000, outbox.legacyRunningGraceMillis)

                val worker = context.getBean(OutboxWorker::class.java)
                assertEquals("boot2-outbox-worker", privateField(worker, "workerId"))
                assertEquals(9_000L, privateField(worker, "leaseDurationMillis"))
                assertEquals(4_000L, privateField(worker, "legacyRunningGraceMillis"))
            }
    }

    @Test
    fun `generates an Outbox worker identity for a blank setting`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues("fileweft.outbox.worker-id=   ")
            .run { context ->
                val worker = context.getBean(OutboxWorker::class.java)
                assertTrue((privateField(worker, "workerId") as String).startsWith("fileweft-outbox-"))
            }
    }

    @Test
    fun `rejects invalid Outbox lease durations during runtime assembly`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues("fileweft.outbox.lease-duration-millis=0")
            .run { context -> assertTrue(context.startupFailure != null) }
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues("fileweft.outbox.legacy-running-grace-millis=-1")
            .run { context -> assertTrue(context.startupFailure != null) }
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues("fileweft.outbox.backlog-metrics-interval-millis=0")
            .run { context -> assertTrue(context.startupFailure != null) }
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues("fileweft.outbox.backlog-metrics-query-timeout-seconds=0")
            .run { context -> assertTrue(context.startupFailure != null) }
    }

    @Test
    fun `binds persisted task lease settings into the runtime worker`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues(
                "fileweft.task.worker-id=boot2-task-worker",
                "fileweft.task.lease-duration-millis=9000",
                "fileweft.task.legacy-running-grace-millis=4000",
            )
            .run { context ->
                val task = context.getBean(FileWeftProperties::class.java).task
                assertEquals("boot2-task-worker", task.workerId)
                assertEquals(9_000, task.leaseDurationMillis)
                assertEquals(4_000, task.legacyRunningGraceMillis)

                val worker = context.getBean(TaskWorker::class.java)
                assertEquals("boot2-task-worker", privateField(worker, "workerId"))
                assertEquals(9_000L, privateField(worker, "leaseDurationMillis"))
                assertEquals(4_000L, privateField(worker, "legacyRunningGraceMillis"))
            }
    }

    @Test
    fun `generates a task worker identity for a blank setting`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues("fileweft.task.worker-id=   ")
            .run { context ->
                val worker = context.getBean(TaskWorker::class.java)
                assertTrue((privateField(worker, "workerId") as String).startsWith("fileweft-"))
                assertEquals(300_000L, privateField(worker, "legacyRunningGraceMillis"))
            }
    }

    @Test
    fun `rejects invalid task lease durations during runtime assembly`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues("fileweft.task.lease-duration-millis=0")
            .run { context -> assertTrue(context.startupFailure != null) }
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues("fileweft.task.legacy-running-grace-millis=-1")
            .run { context -> assertTrue(context.startupFailure != null) }
    }

    @Test
    fun `uses safe identity authorization and explicitly enabled local storage adapters`() {
        contextRunner().run { context ->
            assertNull(context.getBean(UserRealmProvider::class.java).currentUser())
            assertTrue(context.getBean(UserRealmProvider::class.java) is DefaultUserRealmProvider)
            val decision = context.getBean(AuthorizationProvider::class.java).authorize(request())
            assertFalse(decision.allowed)
            assertEquals(DefaultAuthorizationProvider.REASON, decision.reason)
            assertTrue(context.getBean(AuthorizationProvider::class.java) is DefaultAuthorizationProvider)
            assertTrue(context.getBean(StorageAdapter::class.java) is LocalStorageAdapter)
            assertTrue(context.getBean(FileWeftMetrics::class.java) is NoOpFileWeftMetrics)
            assertTrue(context.getBean(TraceContextProvider::class.java) is NoOpTraceContextProvider)
            assertTrue(context.getBean(TraceContextScope::class.java) is NoOpTraceContextProvider)
        }
    }

    @Test
    fun `uses plugin storage without local fallback opt in and ignores invalid local settings`() {
        strictContextRunner()
            .withUserConfiguration(TenantOnlyConfiguration::class.java, PluginConfiguration::class.java)
            .withPropertyValues(
                "fileweft.storage.local-enabled=false",
                "fileweft.storage.local-root=",
            )
            .run { context ->
                assertNull(context.startupFailure)
                assertSame(CustomerStorageAdapter, context.getBean(StorageAdapter::class.java))
                assertTrue(context.getBean(FileWeftPluginRegistry::class.java).plugins().any { it.id() == "test-storage-plugin" })
                assertFalse(context.getBean(FileWeftProperties::class.java).storage.localEnabled)
            }
    }

    @Test
    fun `binds local storage root and makes the default storage usable`() {
        contextRunner().run { context ->
            val storage = context.getBean(StorageAdapter::class.java)
            val stored = storage.upload(
                StorageUploadRequest(Identifier("tenant-a"), "sample.txt", 2, "text/plain"),
                "ok".byteInputStream(),
            )

            assertTrue(storage.accessUrl(stored.location, Duration.ofMinutes(1)).toString().startsWith(storageRoot.toUri().toString()))
            storage.delete(stored.location)
        }
    }

    @Test
    fun `deployment safety Doctor warns when explicit bootstrap adapters are active`() {
        strictContextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues(
                "fileweft.default-tenant-enabled=true",
                "fileweft.default-tenant-id=tenant-a",
                "fileweft.storage.local-enabled=true",
                "fileweft.storage.local-root=${storageRoot.toAbsolutePath()}",
            )
            .run { context ->
                assertNull(context.startupFailure)
                val direct = context.getBean(DeploymentSafetyDoctorChecker::class.java)
                    .check(DoctorCheckContext(Identifier("tenant-a")))
                assertEquals(DoctorStatus.WARNING, direct.status)
                assertEquals("fixed-tenant,local-filesystem", direct.evidence["activeModes"])

                val wired = context.getBean(DoctorApplicationService::class.java)
                    .inspectDocumentAsSystem(Identifier("tenant-a"), Identifier("document-a"))
                    .checks
                    .single { check -> check.checkerName == DeploymentSafetyDoctorChecker.NAME }
                assertEquals(DoctorStatus.WARNING, wired.status)
            }
    }

    @Test
    fun `deployment safety Doctor is healthy for customer tenant and storage beans`() {
        strictContextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java, CustomerConfiguration::class.java)
            .run { context ->
                assertNull(context.startupFailure)
                val properties = context.getBean(FileWeftProperties::class.java)
                assertFalse(properties.defaultTenantEnabled)
                assertTrue(properties.defaultTenantId.isEmpty())
                assertFalse(properties.storage.localEnabled)
                assertTrue(properties.storage.localRoot.isEmpty())
                val direct = context.getBean(DeploymentSafetyDoctorChecker::class.java)
                    .check(DoctorCheckContext(Identifier("customer")))
                assertEquals(DoctorStatus.HEALTHY, direct.status)
                assertTrue(direct.evidence.isEmpty())

                val wired = context.getBean(DoctorApplicationService::class.java)
                    .inspectDocumentAsSystem(Identifier("customer"), Identifier("document-a"))
                    .checks
                    .single { check -> check.checkerName == DeploymentSafetyDoctorChecker.NAME }
                assertEquals(DoctorStatus.HEALTHY, wired.status)
            }
    }

    @Test
    fun `adapts the host Micrometer registry without replacing a customer metrics bean`() {
        contextRunner().withUserConfiguration(MicrometerConfiguration::class.java).run { context ->
            assertTrue(context.getBean(FileWeftMetrics::class.java) is MicrometerFileWeftMetrics)
            assertTrue(context.getBean(FileWeftGaugeRecorder::class.java) is MicrometerFileWeftGauges)
        }
    }

    @Test
    fun `wires bounded persistent Outbox backlog observation through replaceable ports`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java, MicrometerConfiguration::class.java)
            .withPropertyValues(
                "fileweft.worker.enabled=true",
                "fileweft.outbox.backlog-metrics-interval-millis=9000",
                "fileweft.outbox.backlog-metrics-query-timeout-seconds=7",
            )
            .run { context ->
                assertEquals(9_000, context.getBean(FileWeftProperties::class.java).outbox.backlogMetricsIntervalMillis)
                assertEquals(7, context.getBean(FileWeftProperties::class.java).outbox.backlogMetricsQueryTimeoutSeconds)
                val reader = context.getBean(OutboxBacklogReader::class.java)
                assertTrue(reader is JdbcOutboxBacklogReader)
                assertEquals(7, privateField(reader, "queryTimeoutSeconds"))
                assertTrue(context.getBean(OutboxBacklogMetricsPublisher::class.java) != null)
                assertTrue(context.getBean(FileWeftGaugeRecorder::class.java) is MicrometerFileWeftGauges)
                assertTrue(context.containsBean("fileWeftOutboxBacklogMetricsExecutor"))
                assertEquals(
                    9_000L,
                    privateField(context.getBean(OutboxBacklogMetricsPublisher::class.java), "samplingIntervalMillis"),
                )
            }
    }

    @Test
    fun `allows hosts to disable default persistent Outbox observation and its worker lane`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues(
                "fileweft.worker.enabled=true",
                "fileweft.outbox.backlog-metrics-enabled=false",
            )
            .run { context ->
                assertFalse(context.getBean(FileWeftProperties::class.java).outbox.backlogMetricsEnabled)
                assertTrue(context.getBeansOfType(OutboxBacklogReader::class.java).isEmpty())
                assertTrue(context.getBeansOfType(OutboxBacklogMetricsPublisher::class.java).isEmpty())
                assertFalse(context.containsBean("fileWeftOutboxBacklogMetricsExecutor"))
                assertTrue(context.getBean(FileWeftWorkerScheduler::class.java) != null)
            }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `assembles one categorized runtime configuration with stable bean names`() {
        contextRunner().withUserConfiguration(DatabaseConfiguration::class.java).run { context ->
            assertEquals(1, context.getBeansOfType(FileWeftRuntimeConfiguration::class.java).size)
            assertEquals(1, context.getBeansOfType(FileWeftDocumentConfiguration::class.java).size)
            assertEquals(1, context.getBeansOfType(FileWeftUploadConfiguration::class.java).size)
            assertEquals(1, context.getBeansOfType(FileWeftWorkflowConfiguration::class.java).size)
            assertEquals(1, context.getBeansOfType(FileWeftDoctorConfiguration::class.java).size)
            assertEquals(1, context.getBeansOfType(FileWeftDeliveryConfiguration::class.java).size)

            listOf(
                "fileWeftTransaction",
                "fileWeftDocumentRepository",
                "fileWeftMetadataSchemaRegistry",
                "fileWeftMetadataProcessor",
                "fileWeftMetadataSchemaQueryService",
                "fileWeftDocumentMetadataService",
                "fileWeftDocumentMetadataWriteService",
                "fileWeftResumableUploadService",
                "fileWeftReviewWorkflowService",
                "fileWeftDoctorService",
                "fileWeftMetadataDoctorChecker",
                "fileWeftDocumentDeliverySyncService",
            ).forEach { beanName -> assertTrue(context.containsBean(beanName), beanName) }

            assertEquals(1, context.getBeansOfType(ResumableUploadService::class.java).size)
            assertEquals(1, context.getBeansOfType(MetadataSchemaRegistry::class.java).size)
            assertEquals(1, context.getBeansOfType(MetadataProcessor::class.java).size)
            assertEquals(1, context.getBeansOfType(MetadataSchemaQueryService::class.java).size)
            assertEquals(1, context.getBeansOfType(DocumentMetadataService::class.java).size)
            assertEquals(1, context.getBeansOfType(DocumentMetadataWriteService::class.java).size)
            assertEquals(1, context.getBeansOfType(MetadataDoctorChecker::class.java).size)
            assertNull(context.getBean(MetadataSchemaRegistry::class.java).findCurrent("missing"))
            assertTrue(
                context.getBean(DoctorApplicationService::class.java)
                    .inspectDocumentAsSystem(Identifier("tenant-a"), Identifier("document-a"))
                    .checks.any { check -> check.checkerName == MetadataDoctorChecker.NAME },
            )
            assertEquals(1, context.getBeansOfType(DocumentReviewWorkflowService::class.java).size)
            assertEquals(1, context.getBeansOfType(DoctorApplicationService::class.java).size)
            assertEquals(1, context.getBeansOfType(DocumentDeliverySyncService::class.java).size)
        }
    }

    @Test
    fun `backs off the default metadata registry for a custom resolver`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                MetadataSchemaContributionsConfiguration::class.java,
                CustomMetadataResolverConfiguration::class.java,
            )
            .run { context ->
                assertTrue(context.getBeansOfType(MetadataSchemaRegistry::class.java).isEmpty())
                assertSame(
                    context.getBean("customMetadataSchemaResolver"),
                    context.getBean(MetadataSchemaResolver::class.java),
                )
                assertEquals(1, context.getBeansOfType(MetadataProcessor::class.java).size)
                assertEquals(1, context.getBeansOfType(DocumentMetadataWriteService::class.java).size)
            }
    }

    @Test
    fun `default metadata registry keeps current and exact historical contributions distinct`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                MetadataSchemaContributionsConfiguration::class.java,
            )
            .run { context ->
                val registry = context.getBean(MetadataSchemaRegistry::class.java)
                val current = context.getBean("currentDocumentMetadataSchema", MetadataSchema::class.java)
                val historical = context.getBean(HistoricalMetadataSchema::class.java).schema

                assertSame(current, registry.findCurrent("document"))
                assertSame(historical, registry.findExact("document", "1"))
                assertSame(
                    current,
                    registry.resolve(MetadataSchemaContext("tenant-a", "document", "DOCUMENT", "UPLOAD")),
                )
                assertSame(
                    historical,
                    registry.resolve(MetadataSchemaContext("tenant-a", "document", "DOCUMENT", "DOCTOR", "1")),
                )
            }
    }

    @Test
    fun `assembles persistence backed runtime services when a data source is available`() {
        contextRunner().withUserConfiguration(DatabaseConfiguration::class.java).run { context ->
            assertTrue(context.getBean(ApplicationTransaction::class.java) != null)
            assertTrue(context.getBean(DocumentRepository::class.java) != null)
            assertTrue(context.getBean(DocumentQueryRepository::class.java) is JdbcDocumentQueryRepository)
            assertTrue(context.getBean(DocumentQueryService::class.java) != null)
            assertTrue(context.getBeansOfType(DocumentFolderReadAccess::class.java).isEmpty())
            assertNull(privateField(context.getBean(DocumentQueryService::class.java), "folderReadAccess"))
            assertTrue(context.getBean(WorkflowQueryRepository::class.java) is JdbcWorkflowQueryRepository)
            assertTrue(context.getBean(WorkflowQueryService::class.java) != null)
            assertNull(privateField(context.getBean(WorkflowQueryService::class.java), "folderReadAccess"))
            assertTrue(
                context.getBean(WorkflowDecisionEvidenceQueryRepository::class.java) is
                    JdbcWorkflowDecisionEvidenceQueryRepository,
            )
            assertTrue(context.getBean(WorkflowDecisionEvidenceQueryService::class.java) != null)
            assertNull(privateField(context.getBean(WorkflowDecisionEvidenceQueryService::class.java), "folderReadAccess"))
            assertTrue(context.getBean(WorkflowInstanceRepository::class.java) != null)
            assertTrue(context.getBean(UploadApplicationService::class.java) != null)
            assertTrue(context.getBean(ResumableUploadService::class.java) != null)
            assertTrue(context.getBean(ResumableUploadSessionRepository::class.java) != null)
            assertTrue(context.getBean(RequestIdempotencyRepository::class.java) is JdbcRequestIdempotencyRepository)
            assertTrue(context.getBean(RequestIdempotencyService::class.java) != null)
            assertTrue(context.getBean(IdempotentDocumentLifecycleService::class.java) != null)
            assertTrue(context.getBeansOfType(IdempotentDocumentCatalogLifecycleService::class.java).isEmpty())
            assertTrue(context.getBean(IdempotentDocumentReviewWorkflowService::class.java) != null)
            assertTrue(context.getBeansOfType(IdempotentDocumentCatalogReviewWorkflowService::class.java).isEmpty())
            assertTrue(context.getBean(DocumentDraftService::class.java) != null)
            assertTrue(context.getBeansOfType(DocumentCatalogDraftService::class.java).isEmpty())
            assertTrue(context.getBeansOfType(DocumentCatalogMutationService::class.java).isEmpty())
            assertTrue(context.getBeansOfType(DocumentCatalogLifecycleService::class.java).isEmpty())
            assertTrue(context.getBean(DocumentDownloadService::class.java) != null)
            assertTrue(context.getBean(ArchiveDocumentService::class.java) != null)
            assertTrue(context.getBean(DoctorApplicationService::class.java) != null)
            assertTrue(
                context.getBean(DocumentDoctorTaskQueryRepository::class.java) is
                    JdbcDocumentDoctorTaskQueryRepository,
            )
            assertTrue(context.getBean(DocumentDoctorQueryService::class.java) != null)
            assertTrue(context.getBean(DocumentDoctorTaskQueryService::class.java) != null)
            assertTrue(context.getBean(SystemDoctorService::class.java) != null)
            assertTrue(context.getBean(IdempotentScheduleDocumentDoctorService::class.java) != null)
            assertTrue(context.getBeansOfType(IdempotentScheduleDocumentCatalogDoctorService::class.java).isEmpty())
            assertNull(privateField(context.getBean(DocumentDoctorQueryService::class.java), "folderReadAccess"))
            assertNull(privateField(context.getBean(DocumentDoctorTaskQueryService::class.java), "folderReadAccess"))
            assertTrue(context.getBean(TaskMutationRepository::class.java) != null)
            assertTrue(privateField(context.getBean(DocumentDoctorTaskHandler::class.java), "taskMutations") is TaskMutationRepository)
            assertTrue(context.getBean(OutboxWorker::class.java) != null)
            assertTrue(context.getBean(OutboxBacklogReader::class.java) != null)
            assertTrue(context.getBean(OutboxBacklogMetricsPublisher::class.java) != null)
            assertTrue(context.getBean(OperationLogRepository::class.java) != null)
            assertTrue(context.getBeansOfType(AgentResultRepository::class.java).isEmpty())
            assertTrue(context.getBeansOfType(ConfirmAgentSuggestionService::class.java).isEmpty())
            assertTrue(context.getBeansOfType(AgentTaskOrchestrator::class.java).isEmpty())
            assertTrue(context.getBeansOfType(AgentDoctorChecker::class.java).isEmpty())
            assertTrue(context.getBeansOfType(AgentTaskScheduler::class.java).isEmpty())
            assertTrue(context.getBeansOfType(AgentTaskHandler::class.java).isEmpty())
            assertTrue(context.getBeansOfType(AgentTaskOutboxEventHandler::class.java).isEmpty())
            assertTrue(context.getBeansOfType(PersistedAgentSuggestionConfirmationService::class.java).isEmpty())
            assertTrue(context.getBean(DeliveryProfileDoctorChecker::class.java) != null)
            assertTrue(context.getBean(WorkflowDoctorChecker::class.java) != null)
            assertTrue(context.getBean(ConnectorInvocationExecutor::class.java) != null)
            assertTrue(context.getBean(ConnectorResiliencePolicy::class.java) != null)
            assertTrue(context.getBean(ConnectorResilienceRegistry::class.java) != null)
            assertTrue(context.getBean(DocumentReviewRouteResolver::class.java).routeIds().contains("default"))
            assertTrue(
                context.getBean(DoctorApplicationService::class.java)
                    .inspectDocumentAsSystem(Identifier("tenant-a"), Identifier("document-a"))
                    .checks.any { it.checkerName == DeliveryProfileDoctorChecker.NAME },
            )
            assertTrue(
                context.getBean(DoctorApplicationService::class.java)
                    .inspectDocumentAsSystem(Identifier("tenant-a"), Identifier("document-a"))
                    .checks.any { it.checkerName == WorkflowDoctorChecker.NAME },
            )
            assertFalse(
                context.getBean(DoctorApplicationService::class.java)
                    .inspectDocumentAsSystem(Identifier("tenant-a"), Identifier("document-a"))
                    .checks.any { it.checkerName == AgentDoctorChecker.NAME },
            )
        }
    }

    @Test
    fun `enables legacy Agent auto configuration only through the compatibility property`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues("fileweft.compatibility.legacy-agent-autoconfiguration-enabled=true")
            .run { context ->
                assertNull(context.startupFailure)
                assertTrue(context.getBean(AgentResultRepository::class.java) != null)
                assertTrue(context.getBean(ConfirmAgentSuggestionService::class.java) != null)
                assertTrue(privateField(context.getBean(ConfirmAgentSuggestionService::class.java), "tasks") is TaskRepository)
                assertTrue(context.getBean(AgentTaskOrchestrator::class.java) != null)
                assertTrue(context.getBean(AgentDoctorChecker::class.java) != null)
                assertTrue(context.getBean(AgentTaskScheduler::class.java) != null)
                assertTrue(context.getBean(AgentTaskHandler::class.java) != null)
                assertTrue(privateField(context.getBean(AgentTaskHandler::class.java), "taskMutations") is TaskMutationRepository)
                assertTrue(context.getBean(AgentTaskOutboxEventHandler::class.java) != null)
                assertTrue(context.getBean(PersistedAgentSuggestionConfirmationService::class.java) != null)
                assertTrue(
                    privateField(context.getBean(PersistedAgentSuggestionConfirmationService::class.java), "tasks") is TaskRepository,
                )
                assertTrue(
                    context.getBean(DoctorApplicationService::class.java)
                        .inspectDocumentAsSystem(Identifier("tenant-a"), Identifier("document-a"))
                        .checks.any { it.checkerName == AgentDoctorChecker.NAME },
                )
            }
    }

    @Test
    fun `backs off request idempotency repository and service for customer beans`() {
        contextRunner().withUserConfiguration(
            DatabaseConfiguration::class.java,
            CustomerRequestIdempotencyConfiguration::class.java,
        ).run { context ->
            assertSame(
                context.getBean("customerRequestIdempotencyRepository"),
                context.getBean(RequestIdempotencyRepository::class.java),
            )
            assertSame(
                context.getBean("customerRequestIdempotencyService"),
                context.getBean(RequestIdempotencyService::class.java),
            )
        }
    }

    @Test
    fun `backs off the flat idempotent lifecycle boundary for a customer bean`() {
        contextRunner().withUserConfiguration(
            DatabaseConfiguration::class.java,
            CustomerIdempotentLifecycleConfiguration::class.java,
        ).run { context ->
            assertSame(
                context.getBean("customerIdempotentDocumentLifecycleService"),
                context.getBean(IdempotentDocumentLifecycleService::class.java),
            )
            assertTrue(context.getBeansOfType(IdempotentDocumentCatalogLifecycleService::class.java).isEmpty())
        }
    }

    @Test
    fun `backs off the guarded idempotent lifecycle boundary for a customer bean`() {
        contextRunner().withUserConfiguration(
            DatabaseConfiguration::class.java,
            CustomerConfiguration::class.java,
            CatalogConfiguration::class.java,
            CustomerIdempotentCatalogLifecycleConfiguration::class.java,
        ).run { context ->
            assertTrue(context.getBeansOfType(IdempotentDocumentLifecycleService::class.java).isEmpty())
            assertSame(
                context.getBean("customerIdempotentDocumentCatalogLifecycleService"),
                context.getBean(IdempotentDocumentCatalogLifecycleService::class.java),
            )
        }
    }

    @Test
    fun `does not add a guarded lifecycle boundary beside an explicit flat customer boundary`() {
        contextRunner().withUserConfiguration(
            DatabaseConfiguration::class.java,
            CustomerConfiguration::class.java,
            CatalogConfiguration::class.java,
            CustomerIdempotentLifecycleConfiguration::class.java,
        ).run { context ->
            assertSame(
                context.getBean("customerIdempotentDocumentLifecycleService"),
                context.getBean(IdempotentDocumentLifecycleService::class.java),
            )
            assertTrue(context.getBeansOfType(IdempotentDocumentCatalogLifecycleService::class.java).isEmpty())
        }
    }

    @Test
    fun `combines a host review route with the configured default route id`() {
        contextRunner().withUserConfiguration(DatabaseConfiguration::class.java, ReviewRouteConfiguration::class.java)
            .withPropertyValues("fileweft.workflow.default-review-route-id=host-route")
            .run { context ->
                val routes = context.getBean(DocumentReviewRouteResolver::class.java)
                assertTrue(routes.routeIds().containsAll(setOf("default", "host-route")))
            }
    }

    @Test
    fun `auto configures catalog integration only when the host provides one catalog provider`() {
        contextRunner().withUserConfiguration(DatabaseConfiguration::class.java, CustomerConfiguration::class.java, CatalogConfiguration::class.java)
            .run { context ->
                assertTrue(context.getBean(DocumentCatalogAccessService::class.java) != null)
                assertTrue(context.containsBean("fileWeftDocumentCatalogAccessService"))
                assertTrue(context.getBean(DocumentCatalogBindingService::class.java) != null)
                assertTrue(context.getBean(DocumentCatalogDraftService::class.java) != null)
                assertTrue(context.getBean(DocumentCatalogMutationService::class.java) != null)
                assertTrue(context.getBean(DocumentCatalogLifecycleService::class.java) != null)
                assertTrue(context.getBeansOfType(IdempotentDocumentLifecycleService::class.java).isEmpty())
                assertTrue(context.getBean(IdempotentDocumentCatalogLifecycleService::class.java) != null)
                assertTrue(context.getBeansOfType(IdempotentDocumentReviewWorkflowService::class.java).isEmpty())
                assertTrue(context.getBean(IdempotentDocumentCatalogReviewWorkflowService::class.java) != null)
                assertTrue(context.getBean(FileAssetMutationRepository::class.java) != null)
                assertTrue(context.getBean(JdbcFileAssetRepository::class.java) is FileAssetMutationRepository)
                assertTrue(context.getBean(CatalogDoctorChecker::class.java) != null)
                assertTrue(context.getBeansOfType(IdempotentScheduleDocumentDoctorService::class.java).isEmpty())
                assertTrue(context.getBean(IdempotentScheduleDocumentCatalogDoctorService::class.java) != null)
                assertSame(
                    context.getBean(DocumentCatalogAccessService::class.java),
                    context.getBean(DocumentFolderReadAccess::class.java),
                )
                assertSame(
                    context.getBean(DocumentCatalogAccessService::class.java),
                    privateField(context.getBean(DocumentQueryService::class.java), "folderReadAccess"),
                )
                assertSame(
                    context.getBean(DocumentCatalogAccessService::class.java),
                    privateField(context.getBean(WorkflowQueryService::class.java), "folderReadAccess"),
                )
                assertSame(
                    context.getBean(DocumentCatalogAccessService::class.java),
                    privateField(context.getBean(WorkflowDecisionEvidenceQueryService::class.java), "folderReadAccess"),
                )
                assertSame(
                    context.getBean(DocumentCatalogAccessService::class.java),
                    privateField(context.getBean(DocumentDoctorQueryService::class.java), "folderReadAccess"),
                )
                assertSame(
                    context.getBean(DocumentCatalogAccessService::class.java),
                    privateField(context.getBean(DocumentDoctorTaskQueryService::class.java), "folderReadAccess"),
                )
            }
    }

    @Test
    fun `keeps catalog access and draft creation available when asset mutation locking is unavailable`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                CustomerConfiguration::class.java,
                CatalogConfiguration::class.java,
                PlainFileAssetRepositoryConfiguration::class.java,
            )
            .run { context ->
                assertTrue(context.getBean(DocumentCatalogAccessService::class.java) != null)
                assertTrue(context.getBean(DocumentCatalogDraftService::class.java) != null)
                assertTrue(context.getBeansOfType(DocumentCatalogBindingService::class.java).isEmpty())
                assertTrue(context.getBeansOfType(DocumentCatalogMutationService::class.java).isEmpty())
                assertTrue(context.getBeansOfType(DocumentCatalogLifecycleService::class.java).isEmpty())
                assertTrue(context.getBeansOfType(IdempotentDocumentLifecycleService::class.java).isEmpty())
                assertTrue(context.getBeansOfType(IdempotentDocumentCatalogLifecycleService::class.java).isEmpty())
                assertTrue(context.getBeansOfType(IdempotentDocumentReviewWorkflowService::class.java).isEmpty())
                assertTrue(context.getBeansOfType(IdempotentDocumentCatalogReviewWorkflowService::class.java).isEmpty())
                assertTrue(context.getBeansOfType(FileAssetMutationRepository::class.java).isEmpty())
                assertTrue(context.getBeansOfType(IdempotentScheduleDocumentDoctorService::class.java).isEmpty())
                assertTrue(context.getBeansOfType(IdempotentScheduleDocumentCatalogDoctorService::class.java).isEmpty())
            }
    }

    @Test
    fun `assembles catalog services from an explicit access service without catalog providers`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                CustomerConfiguration::class.java,
                ExplicitCatalogAccessConfiguration::class.java,
            )
            .run { context ->
                assertSame(
                    context.getBean("explicitCatalogAccessService"),
                    context.getBean(DocumentCatalogAccessService::class.java),
                )
                assertTrue(context.getBean(DocumentCatalogBindingService::class.java) != null)
                assertTrue(context.getBean(DocumentCatalogDraftService::class.java) != null)
                assertTrue(context.getBean(DocumentCatalogMutationService::class.java) != null)
                assertTrue(context.getBean(DocumentCatalogLifecycleService::class.java) != null)
                assertTrue(context.getBeansOfType(IdempotentDocumentLifecycleService::class.java).isEmpty())
                assertTrue(context.getBean(IdempotentDocumentCatalogLifecycleService::class.java) != null)
            }
    }

    @Test
    fun `assembles catalog services from an explicit access service when catalog providers are multiple`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                CustomerConfiguration::class.java,
                MultipleCatalogConfiguration::class.java,
                ExplicitCatalogAccessConfiguration::class.java,
            )
            .run { context ->
                assertSame(
                    context.getBean("explicitCatalogAccessService"),
                    context.getBean(DocumentCatalogAccessService::class.java),
                )
                assertTrue(context.getBean(DocumentCatalogBindingService::class.java) != null)
                assertTrue(context.getBean(DocumentCatalogDraftService::class.java) != null)
                assertTrue(context.getBean(DocumentCatalogMutationService::class.java) != null)
                assertTrue(context.getBean(DocumentCatalogLifecycleService::class.java) != null)
                assertTrue(context.getBeansOfType(IdempotentDocumentLifecycleService::class.java).isEmpty())
                assertTrue(context.getBean(IdempotentDocumentCatalogLifecycleService::class.java) != null)
            }
    }

    @Test
    fun `fails closed when the host provides multiple catalog providers`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                CustomerConfiguration::class.java,
                MultipleCatalogConfiguration::class.java,
            )
            .run { context ->
                assertTrue(requireNotNull(context.startupFailure).hasCause(NoUniqueBeanDefinitionException::class.java))
            }
    }

    @Test
    fun `fails closed when multiple guarded catalog lifecycle services are present`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                CustomerConfiguration::class.java,
                CatalogConfiguration::class.java,
                MultipleCatalogLifecycleConfiguration::class.java,
            )
            .run { context ->
                assertTrue(requireNotNull(context.startupFailure).hasCause(NoUniqueBeanDefinitionException::class.java))
            }
    }

    @Test
    fun `fails closed when one of multiple catalog providers is primary`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                CustomerConfiguration::class.java,
                PrimaryMultipleCatalogConfiguration::class.java,
            )
            .run { context ->
                assertTrue(requireNotNull(context.startupFailure).hasCause(NoUniqueBeanDefinitionException::class.java))
            }
    }

    @Test
    fun `fails closed for workflow reads when one of multiple catalog access boundaries is primary`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                CustomerConfiguration::class.java,
                MultipleCatalogAccessConfiguration::class.java,
            )
            .run { context ->
                assertTrue(requireNotNull(context.startupFailure).hasCause(NoUniqueBeanDefinitionException::class.java))
            }
    }

    @Test
    fun `enables a scheduler only for the explicit worker deployment role`() {
        contextRunner().withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues("fileweft.worker.enabled=true")
            .run { context ->
                assertTrue(context.getBean(FileWeftWorkerScheduler::class.java) != null)
            }
    }

    @Test
    fun `does not replace a customer document repository when assembling runtime services`() {
        contextRunner().withUserConfiguration(DatabaseWithDocumentRepositoryConfiguration::class.java).run { context ->
            assertSame(context.getBean("customerDocumentRepository"), context.getBean(DocumentRepository::class.java))
        }
    }

    @Test
    fun `fails startup clearly when a custom document repository lacks mutation capability`() {
        contextRunner().withUserConfiguration(DatabaseWithReadOnlyDocumentRepositoryConfiguration::class.java)
            .run { context ->
                assertTrue(context.startupFailure.causeMessages().any { message ->
                    message.contains("must also implement DocumentMutationRepository")
                })
            }
    }

    @Test
    fun `does not replace a customer catalog draft service`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                CustomerConfiguration::class.java,
                CatalogConfiguration::class.java,
                CustomerCatalogDraftConfiguration::class.java,
            )
            .run { context ->
                assertSame(
                    context.getBean("customerCatalogDraftService"),
                    context.getBean(DocumentCatalogDraftService::class.java),
                )
            }
    }

    @Test
    fun `does not replace a customer catalog mutation service`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                CustomerConfiguration::class.java,
                CatalogConfiguration::class.java,
                CustomerCatalogMutationConfiguration::class.java,
            )
            .run { context ->
                assertSame(
                    context.getBean("customerCatalogMutationService"),
                    context.getBean(DocumentCatalogMutationService::class.java),
                )
            }
    }

    @Test
    fun `does not replace a customer catalog lifecycle service`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                CustomerConfiguration::class.java,
                CatalogConfiguration::class.java,
                CustomerCatalogLifecycleConfiguration::class.java,
            )
            .run { context ->
                assertSame(
                    context.getBean("customerCatalogLifecycleService"),
                    context.getBean(DocumentCatalogLifecycleService::class.java),
                )
            }
    }

    @Test
    fun `does not replace customer document query ports or services when assembling runtime services`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java, CustomerDocumentQueryConfiguration::class.java)
            .run { context ->
                assertSame(context.getBean("customerDocumentQueryRepository"), context.getBean(DocumentQueryRepository::class.java))
                assertSame(context.getBean("customerDocumentQueryService"), context.getBean(DocumentQueryService::class.java))
            }
    }

    @Test
    fun `does not replace customer workflow query ports or services when assembling runtime services`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java, CustomerWorkflowQueryConfiguration::class.java)
            .run { context ->
                assertSame(context.getBean("customerWorkflowQueryRepository"), context.getBean(WorkflowQueryRepository::class.java))
                assertSame(context.getBean("customerWorkflowQueryService"), context.getBean(WorkflowQueryService::class.java))
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

    private fun strictContextRunner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FileWeftAutoConfiguration::class.java))

    private fun deliveryProfileProperties(): Array<String> = arrayOf(
        "fileweft.sync.profiles[0].id=first",
        "fileweft.sync.profiles[0].display-name=First delivery",
        "fileweft.sync.profiles[0].targets[0].id=first-target",
        "fileweft.sync.profiles[0].targets[0].display-name=First target",
        "fileweft.sync.profiles[0].targets[0].connector-id=first-connector",
        "fileweft.sync.profiles[1].id=second",
        "fileweft.sync.profiles[1].display-name=Second delivery",
        "fileweft.sync.profiles[1].targets[0].id=second-target",
        "fileweft.sync.profiles[1].targets[0].display-name=Second target",
        "fileweft.sync.profiles[1].targets[0].connector-id=second-connector",
    )

    private fun Throwable?.causeMessages(): List<String> = generateSequence(requireNotNull(this)) { failure -> failure.cause }
        .mapNotNull { failure -> failure.message }
        .toList()

    private fun privateField(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }
        .get(target)

    private fun Throwable.hasCause(type: Class<out Throwable>): Boolean =
        generateSequence(this) { failure -> failure.cause }.any(type::isInstance)

    private fun request() = AuthorizationRequest(
        AuthorizationSubject(Identifier("user"), "USER"),
        AuthorizationResource(Identifier("document"), "DOCUMENT", Identifier("tenant")),
        AuthorizationAction("document:read"),
        AuthorizationEnvironment(),
    )

    @Configuration(proxyBeanMethods = false)
    class CustomerConfiguration {
        @Bean
        fun customerTenantProvider(): TenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("customer"))
        }

        @Bean
        fun customerUserRealmProvider(): UserRealmProvider = object : UserRealmProvider {
            override fun currentUser(): UserIdentity = UserIdentity(Identifier("customer-user"))
            override fun findUser(userId: Identifier): UserIdentity? = null
        }

        @Bean
        fun customerAuthorizationProvider(): AuthorizationProvider = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
        }

        @Bean
        fun customerStorageAdapter(): StorageAdapter = CustomerStorageAdapter
    }

    @Configuration(proxyBeanMethods = false)
    class TenantOnlyConfiguration {
        @Bean
        fun customerTenantProvider(): TenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("customer"))
        }
    }

    @Configuration(proxyBeanMethods = false)
    class StorageOnlyConfiguration {
        @Bean
        fun customerStorageAdapter(): StorageAdapter = CustomerStorageAdapter
    }

    @Configuration(proxyBeanMethods = false)
    class DatabaseConfiguration {
        @Bean
        fun dataSource(): DataSource = StubDataSource
    }

    @Configuration(proxyBeanMethods = false)
    class MetadataSchemaContributionsConfiguration {
        @Bean
        fun currentDocumentMetadataSchema(): MetadataSchema = MetadataSchema(
            "document",
            "2",
            listOf(MetadataField("title", MetadataFieldType.STRING)),
        )

        @Bean
        fun historicalDocumentMetadataSchema(): HistoricalMetadataSchema = HistoricalMetadataSchema(
            MetadataSchema(
                "document",
                "1",
                listOf(MetadataField("legacyTitle", MetadataFieldType.STRING)),
            ),
        )
    }

    @Configuration(proxyBeanMethods = false)
    class CustomMetadataResolverConfiguration {
        @Bean
        fun customMetadataSchemaResolver(): MetadataSchemaResolver = object : MetadataSchemaResolver {
            override fun resolve(
                context: ai.icen.fw.metadata.api.MetadataSchemaContext,
            ): ai.icen.fw.metadata.api.MetadataSchema? = null
        }
    }

    @Configuration(proxyBeanMethods = false)
    class LegacyDeliveryTargetRepositoryConfiguration {
        @Bean
        fun legacyDeliveryTargetRepository(): DocumentDeliveryTargetRepository =
            object : DocumentDeliveryTargetRepository {
                override fun findById(tenantId: Identifier, deliveryId: Identifier): DocumentDeliveryTarget? = null

                override fun findByDocument(
                    tenantId: Identifier,
                    documentId: Identifier,
                ): List<DocumentDeliveryTarget> = emptyList()

                override fun save(target: DocumentDeliveryTarget) = Unit
            }
    }

    @Configuration(proxyBeanMethods = false)
    class LegacyTaskRepositoryConfiguration {
        @Bean
        fun legacyTaskRepository(): LegacyTaskRepository = LegacyTaskRepository()
    }

    class LegacyTaskRepository : TaskRepository, TaskProcessingRepository {
        override fun enqueue(task: BackgroundTask) = Unit
        override fun findById(tenantId: Identifier, taskId: Identifier): BackgroundTask? = null
        override fun findByBusiness(tenantId: Identifier, businessId: Identifier, limit: Int): List<BackgroundTask> = emptyList()
        override fun claimAvailable(
            limit: Int,
            now: Long,
            leaseOwner: String,
            leaseExpiresAt: Long,
        ): List<BackgroundTaskLease> = emptyList()

        override fun markSucceeded(lease: BackgroundTaskLease, completedAt: Long) = Unit
        override fun markForRetry(lease: BackgroundTaskLease, nextAttemptAt: Long, message: String, updatedAt: Long) = Unit
        override fun markFailed(lease: BackgroundTaskLease, message: String, updatedAt: Long) = Unit
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

    @Configuration(proxyBeanMethods = false)
    class CustomerRequestIdempotencyConfiguration {
        @Bean
        fun customerRequestIdempotencyRepository(): RequestIdempotencyRepository =
            CustomerRequestIdempotencyRepository

        @Bean
        fun customerRequestIdempotencyService(
            repository: RequestIdempotencyRepository,
            transaction: ApplicationTransaction,
            identifiers: IdentifierGenerator,
            clock: Clock,
        ): RequestIdempotencyService = RequestIdempotencyService(repository, transaction, identifiers, clock)
    }

    @Configuration(proxyBeanMethods = false)
    class CustomerIdempotentLifecycleConfiguration {
        @Bean
        fun customerIdempotentDocumentLifecycleService(
            commands: DocumentCommandService,
            publish: PublishDocumentService,
            offline: OfflineDocumentService,
            restore: RestoreOfflineDocumentService,
            archive: ArchiveDocumentService,
            idempotency: RequestIdempotencyService,
        ): IdempotentDocumentLifecycleService = IdempotentDocumentLifecycleService(
            commands,
            publish,
            offline,
            restore,
            archive,
            idempotency,
        )
    }

    @Configuration(proxyBeanMethods = false)
    class CustomerIdempotentCatalogLifecycleConfiguration {
        @Bean
        fun customerIdempotentDocumentCatalogLifecycleService(
            catalogLifecycle: DocumentCatalogLifecycleService,
            idempotency: RequestIdempotencyService,
        ): IdempotentDocumentCatalogLifecycleService =
            IdempotentDocumentCatalogLifecycleService(catalogLifecycle, idempotency)
    }

    private object CustomerRequestIdempotencyRepository : RequestIdempotencyRepository {
        override fun findByKeyDigest(tenantId: Identifier, keyDigest: String): RequestIdempotencyRecord? = null

        override fun claim(
            request: RequestIdempotency,
            newRecordId: Identifier,
            now: Long,
        ): RequestIdempotencyClaim = error("The auto-configuration test repository must not be invoked.")

        override fun complete(
            recordId: Identifier,
            tenantId: Identifier,
            keyDigest: String,
            result: IdempotencyResult,
            completedAt: Long,
        ): RequestIdempotencyRecord = error("The auto-configuration test repository must not be invoked.")
    }

    @Configuration(proxyBeanMethods = false)
    class PluginConfiguration {
        @Bean
        fun testStoragePlugin(): FileWeftPlugin = object : FileWeftPlugin {
            override fun id(): String = "test-storage-plugin"
            override fun storageAdapters(): List<StorageAdapter> = listOf(CustomerStorageAdapter)
        }
    }

    @Configuration(proxyBeanMethods = false)
    class ReviewRouteConfiguration {
        @Bean
        fun hostReviewRoute(): DocumentReviewRouteProvider = object : DocumentReviewRouteProvider {
            override fun id(): String = "host-route"
            override fun resolve(request: DocumentReviewRouteRequest): DocumentReviewRoute =
                DocumentReviewRoute("HOST_REVIEW", listOf(DocumentReviewRouteTask()))
        }
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
    class MultipleCatalogConfiguration {
        @Bean
        fun firstCatalogProvider(): DocumentCatalogProvider = object : DocumentCatalogProvider {
            override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> =
                listOf(DocumentCatalogFolder("inbox-a", null, "Inbox A"))
        }

        @Bean
        fun secondCatalogProvider(): DocumentCatalogProvider = object : DocumentCatalogProvider {
            override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> =
                listOf(DocumentCatalogFolder("inbox-b", null, "Inbox B"))
        }
    }

    @Configuration(proxyBeanMethods = false)
    class MultipleCatalogLifecycleConfiguration {
        @Bean
        @Primary
        fun primaryCatalogLifecycleService(
            commands: DocumentCommandService,
            workflows: DocumentReviewWorkflowService,
            publish: PublishDocumentService,
            offline: OfflineDocumentService,
            restore: RestoreOfflineDocumentService,
            archive: ArchiveDocumentService,
            catalogAccess: DocumentCatalogAccessService,
            documents: DocumentRepository,
            assets: FileAssetRepository,
            transaction: ApplicationTransaction,
        ): DocumentCatalogLifecycleService = catalogLifecycle(
            commands, workflows, publish, offline, restore, archive, catalogAccess, documents, assets, transaction,
        )

        @Bean
        fun secondaryCatalogLifecycleService(
            commands: DocumentCommandService,
            workflows: DocumentReviewWorkflowService,
            publish: PublishDocumentService,
            offline: OfflineDocumentService,
            restore: RestoreOfflineDocumentService,
            archive: ArchiveDocumentService,
            catalogAccess: DocumentCatalogAccessService,
            documents: DocumentRepository,
            assets: FileAssetRepository,
            transaction: ApplicationTransaction,
        ): DocumentCatalogLifecycleService = catalogLifecycle(
            commands, workflows, publish, offline, restore, archive, catalogAccess, documents, assets, transaction,
        )

        private fun catalogLifecycle(
            commands: DocumentCommandService,
            workflows: DocumentReviewWorkflowService,
            publish: PublishDocumentService,
            offline: OfflineDocumentService,
            restore: RestoreOfflineDocumentService,
            archive: ArchiveDocumentService,
            catalogAccess: DocumentCatalogAccessService,
            documents: DocumentRepository,
            assets: FileAssetRepository,
            transaction: ApplicationTransaction,
        ): DocumentCatalogLifecycleService = DocumentCatalogLifecycleService(
            commands, workflows, publish, offline, restore, archive, catalogAccess, documents, assets, transaction,
        )
    }

    @Configuration(proxyBeanMethods = false)
    class PrimaryMultipleCatalogConfiguration {
        @Bean
        @Primary
        fun primaryCatalogProvider(): DocumentCatalogProvider = object : DocumentCatalogProvider {
            override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> =
                listOf(DocumentCatalogFolder("primary-inbox", null, "Primary inbox"))
        }

        @Bean
        fun secondaryCatalogProvider(): DocumentCatalogProvider = object : DocumentCatalogProvider {
            override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> =
                listOf(DocumentCatalogFolder("secondary-inbox", null, "Secondary inbox"))
        }
    }

    @Configuration(proxyBeanMethods = false)
    class MultipleCatalogAccessConfiguration {
        @Bean
        @Primary
        fun primaryCatalogAccessService(
            tenants: TenantProvider,
            users: UserRealmProvider,
            authorization: AuthorizationProvider,
        ): DocumentCatalogAccessService = access(tenants, users, authorization, "primary-inbox")

        @Bean
        fun secondaryCatalogAccessService(
            tenants: TenantProvider,
            users: UserRealmProvider,
            authorization: AuthorizationProvider,
        ): DocumentCatalogAccessService = access(tenants, users, authorization, "secondary-inbox")

        @Bean
        fun customerDocumentDownloadVisibility(
            @Qualifier("primaryCatalogAccessService") catalogAccess: DocumentCatalogAccessService,
            queries: DocumentQueryRepository,
        ): DocumentDownloadVisibility = DocumentDownloadVisibility(catalogAccess, queries)

        private fun access(
            tenants: TenantProvider,
            users: UserRealmProvider,
            authorization: AuthorizationProvider,
            folderId: String,
        ): DocumentCatalogAccessService = DocumentCatalogAccessService(
            tenants,
            users,
            authorization,
            object : DocumentCatalogProvider {
                override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> =
                    listOf(DocumentCatalogFolder(folderId, null, folderId))
            },
        )
    }

    @Configuration(proxyBeanMethods = false)
    class ExplicitCatalogAccessConfiguration {
        @Bean
        fun explicitCatalogAccessService(
            tenants: TenantProvider,
            users: UserRealmProvider,
            authorization: AuthorizationProvider,
        ): DocumentCatalogAccessService = DocumentCatalogAccessService(
            tenants,
            users,
            authorization,
            object : DocumentCatalogProvider {
                override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> =
                    listOf(DocumentCatalogFolder("explicit-inbox", null, "Explicit inbox"))
            },
        )
    }

    @Configuration(proxyBeanMethods = false)
    class PlainFileAssetRepositoryConfiguration {
        @Bean
        fun plainFileAssetRepository(): FileAssetRepository = object : FileAssetRepository {
            override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? = null

            override fun save(fileAsset: FileAsset) = Unit
        }
    }

    @Configuration(proxyBeanMethods = false)
    class CustomerCatalogDraftConfiguration {
        @Bean
        fun customerCatalogDraftService(
            drafts: DocumentDraftService,
            catalogAccess: DocumentCatalogAccessService,
        ): DocumentCatalogDraftService = DocumentCatalogDraftService(drafts, catalogAccess)
    }

    @Configuration(proxyBeanMethods = false)
    class CustomerCatalogMutationConfiguration {
        @Bean
        fun customerCatalogMutationService(
            drafts: DocumentDraftService,
            catalogAccess: DocumentCatalogAccessService,
        ): DocumentCatalogMutationService =
            DocumentCatalogMutationService(drafts, catalogAccess)
    }

    @Configuration(proxyBeanMethods = false)
    class CustomerCatalogLifecycleConfiguration {
        @Bean
        fun customerCatalogLifecycleService(
            commands: DocumentCommandService,
            workflows: DocumentReviewWorkflowService,
            publish: PublishDocumentService,
            offline: OfflineDocumentService,
            restore: RestoreOfflineDocumentService,
            archive: ArchiveDocumentService,
            catalogAccess: DocumentCatalogAccessService,
            documents: DocumentRepository,
            assets: FileAssetRepository,
            transaction: ApplicationTransaction,
        ): DocumentCatalogLifecycleService = DocumentCatalogLifecycleService(
            commands,
            workflows,
            publish,
            offline,
            restore,
            archive,
            catalogAccess,
            documents,
            assets,
            transaction,
        )
    }

    @Configuration(proxyBeanMethods = false)
    class MicrometerConfiguration {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    @Configuration(proxyBeanMethods = false)
    class DatabaseWithDocumentRepositoryConfiguration {
        @Bean
        fun dataSource(): DataSource = StubDataSource

        @Bean
        fun customerDocumentRepository(): DocumentRepository = object : DocumentMutationRepository {
            override fun findById(tenantId: Identifier, documentId: Identifier) = null
            override fun findForMutation(tenantId: Identifier, documentId: Identifier) = null
            override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String) = null
            override fun save(document: ai.icen.fw.domain.document.Document) = Unit
        }
    }

    @Configuration(proxyBeanMethods = false)
    class DatabaseWithReadOnlyDocumentRepositoryConfiguration {
        @Bean
        fun dataSource(): DataSource = StubDataSource

        @Bean
        fun readOnlyDocumentRepository(): DocumentRepository = object : DocumentRepository {
            override fun findById(tenantId: Identifier, documentId: Identifier) = null
            override fun save(document: ai.icen.fw.domain.document.Document) = Unit
        }
    }

    @Configuration(proxyBeanMethods = false)
    class CustomerDocumentQueryConfiguration {
        @Bean
        fun customerDocumentQueryRepository(): DocumentQueryRepository = object : DocumentQueryRepository {
            override fun findDetail(
                tenantId: Identifier,
                documentId: Identifier,
                folderReadScope: ai.icen.fw.application.document.DocumentFolderReadScope?,
            ) = null
            override fun findPage(
                tenantId: Identifier,
                request: DocumentPageRequest,
                folderReadScope: ai.icen.fw.application.document.DocumentFolderReadScope?,
            ) = DocumentPageResult(emptyList())
        }

        @Bean
        fun customerDocumentQueryService(
            tenants: TenantProvider,
            users: UserRealmProvider,
            authorization: AuthorizationProvider,
            queries: DocumentQueryRepository,
            transaction: ApplicationTransaction,
        ): DocumentQueryService = DocumentQueryService(tenants, users, authorization, queries, transaction)
    }

    @Configuration(proxyBeanMethods = false)
    class CustomerWorkflowQueryConfiguration {
        @Bean
        fun customerWorkflowQueryRepository(): WorkflowQueryRepository = object : WorkflowQueryRepository {
            override fun findPendingTaskPage(
                tenantId: Identifier,
                currentUserId: Identifier,
                request: WorkflowTaskPageRequest,
                folderReadScope: ai.icen.fw.application.document.DocumentFolderReadScope?,
            ) = WorkflowTaskPageResult(emptyList())

            override fun findDocumentWorkflowPage(
                tenantId: Identifier,
                documentId: Identifier,
                request: DocumentWorkflowPageRequest,
                folderReadScope: ai.icen.fw.application.document.DocumentFolderReadScope?,
            ) = DocumentWorkflowPageResult(emptyList())
        }

        @Bean
        fun customerWorkflowQueryService(
            tenants: TenantProvider,
            users: UserRealmProvider,
            authorization: AuthorizationProvider,
            queries: WorkflowQueryRepository,
            transaction: ApplicationTransaction,
        ): WorkflowQueryService = WorkflowQueryService(tenants, users, authorization, queries, transaction)
    }

    private object CustomerStorageAdapter : StorageAdapter {
        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject = unsupported()
        override fun download(location: StorageObjectLocation): StorageDownload = unsupported()
        override fun delete(location: StorageObjectLocation) = unsupported<Unit>()
        override fun exists(location: StorageObjectLocation): Boolean = unsupported()
        override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI = unsupported()
        override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload = unsupported()
        override fun uploadPart(upload: MultipartUpload, partNumber: Int, content: InputStream, contentLength: Long): MultipartPart = unsupported()
        override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject = unsupported()
        override fun abortMultipartUpload(upload: MultipartUpload) = unsupported<Unit>()

        private fun <T> unsupported(): T = throw UnsupportedOperationException("Test double")
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
}
