package com.fileweft.starter.boot2

import com.fileweft.adapter.authorization.DefaultAuthorizationProvider
import com.fileweft.adapter.connector.ConnectorInvocationExecutor
import com.fileweft.adapter.connector.ConnectorResiliencePolicy
import com.fileweft.adapter.connector.ConnectorResilienceRegistry
import com.fileweft.agent.AgentTaskHandler
import com.fileweft.agent.AgentDoctorChecker
import com.fileweft.agent.AgentTaskOutboxEventHandler
import com.fileweft.agent.PersistedAgentSuggestionConfirmationService
import com.fileweft.application.agent.AgentResultRepository
import com.fileweft.application.agent.ConfirmAgentSuggestionService
import com.fileweft.adapter.identity.DefaultUserRealmProvider
import com.fileweft.adapter.storage.LocalStorageAdapter
import com.fileweft.adapter.observability.NoOpFileWeftMetrics
import com.fileweft.adapter.micrometer.MicrometerFileWeftGauges
import com.fileweft.adapter.micrometer.MicrometerFileWeftMetrics
import com.fileweft.adapter.observability.NoOpTraceContextProvider
import com.fileweft.application.archive.ArchiveDocumentService
import com.fileweft.application.catalog.DocumentCatalogAccessService
import com.fileweft.application.catalog.DocumentCatalogBindingService
import com.fileweft.application.catalog.DocumentCatalogDraftService
import com.fileweft.application.catalog.DocumentCatalogLifecycleService
import com.fileweft.application.catalog.DocumentCatalogMutationService
import com.fileweft.application.doctor.CatalogDoctorChecker
import com.fileweft.application.doctor.DeliveryProfileDoctorChecker
import com.fileweft.application.doctor.DoctorApplicationService
import com.fileweft.application.doctor.WorkflowDoctorChecker
import com.fileweft.application.delivery.DocumentDeliverySyncService
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.application.document.DocumentCommandService
import com.fileweft.application.document.DocumentDownloadService
import com.fileweft.application.document.DocumentDownloadVisibility
import com.fileweft.application.document.DocumentFolderReadAccess
import com.fileweft.application.document.DocumentPageRequest
import com.fileweft.application.document.DocumentPageResult
import com.fileweft.application.document.DocumentQueryRepository
import com.fileweft.application.document.DocumentQueryService
import com.fileweft.application.idempotency.IdempotencyResult
import com.fileweft.application.idempotency.RequestIdempotency
import com.fileweft.application.idempotency.RequestIdempotencyClaim
import com.fileweft.application.idempotency.RequestIdempotencyRecord
import com.fileweft.application.idempotency.RequestIdempotencyRepository
import com.fileweft.application.idempotency.RequestIdempotencyService
import com.fileweft.application.lifecycle.IdempotentDocumentCatalogLifecycleService
import com.fileweft.application.lifecycle.IdempotentDocumentLifecycleService
import com.fileweft.application.upload.ResumableUploadService
import com.fileweft.application.upload.ResumableUploadSessionRepository
import com.fileweft.application.outbox.OutboxWorker
import com.fileweft.application.outbox.OutboxBacklogMetricsPublisher
import com.fileweft.application.outbox.OutboxBacklogReader
import com.fileweft.application.offline.OfflineDocumentService
import com.fileweft.application.offline.RestoreOfflineDocumentService
import com.fileweft.application.publish.PublishDocumentService
import com.fileweft.application.task.TaskWorker
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.application.upload.UploadApplicationService
import com.fileweft.persistence.jdbc.JdbcOutboxBacklogReader
import com.fileweft.persistence.jdbc.JdbcDocumentQueryRepository
import com.fileweft.persistence.jdbc.JdbcFileAssetRepository
import com.fileweft.persistence.jdbc.JdbcRequestIdempotencyRepository
import com.fileweft.persistence.jdbc.JdbcWorkflowQueryRepository
import com.fileweft.application.workflow.DocumentReviewRouteResolver
import com.fileweft.application.workflow.DocumentReviewWorkflowService
import com.fileweft.application.workflow.DocumentWorkflowPageRequest
import com.fileweft.application.workflow.DocumentWorkflowPageResult
import com.fileweft.application.workflow.IdempotentDocumentCatalogReviewWorkflowService
import com.fileweft.application.workflow.IdempotentDocumentReviewWorkflowService
import com.fileweft.application.workflow.WorkflowQueryRepository
import com.fileweft.application.workflow.WorkflowQueryService
import com.fileweft.application.workflow.WorkflowTaskPageRequest
import com.fileweft.application.workflow.WorkflowTaskPageResult
import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.runtime.plugin.FileWeftPluginRegistry
import com.fileweft.spi.plugin.FileWeftPlugin
import com.fileweft.spi.catalog.DocumentCatalogFolder
import com.fileweft.spi.catalog.DocumentCatalogProvider
import com.fileweft.spi.tenant.TenantProvider
import com.fileweft.spi.observability.FileWeftMetrics
import com.fileweft.spi.observability.FileWeftGaugeRecorder
import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.spi.observability.TraceContextScope
import com.fileweft.domain.operation.OperationLogRepository
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.file.FileAsset
import com.fileweft.domain.file.FileAssetMutationRepository
import com.fileweft.domain.file.FileAssetRepository
import com.fileweft.domain.workflow.WorkflowInstanceRepository
import com.fileweft.spi.authorization.AuthorizationAction
import com.fileweft.spi.authorization.AuthorizationEnvironment
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.authorization.AuthorizationResource
import com.fileweft.spi.authorization.AuthorizationSubject
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.storage.MultipartPart
import com.fileweft.spi.storage.MultipartUpload
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.storage.StorageDownload
import com.fileweft.spi.storage.StorageObjectLocation
import com.fileweft.spi.storage.StorageUploadRequest
import com.fileweft.spi.storage.StoredObject
import com.fileweft.spi.workflow.DocumentReviewRoute
import com.fileweft.spi.workflow.DocumentReviewRouteProvider
import com.fileweft.spi.workflow.DocumentReviewRouteRequest
import com.fileweft.spi.workflow.DocumentReviewRouteTask
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.io.InputStream
import java.net.URI
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
    fun `does not replace customer extension beans`() {
        contextRunner().withUserConfiguration(CustomerConfiguration::class.java).run { context ->
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
    fun `defaults to safe identity authorization and local storage adapters`() {
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
    fun `uses plugin storage ahead of the default while retaining plugin diagnostics`() {
        contextRunner().withUserConfiguration(PluginConfiguration::class.java).run { context ->
            assertSame(CustomerStorageAdapter, context.getBean(StorageAdapter::class.java))
            assertTrue(context.getBean(FileWeftPluginRegistry::class.java).plugins().any { it.id() == "test-storage-plugin" })
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
            assertTrue(context.getBean(OutboxWorker::class.java) != null)
            assertTrue(context.getBean(OutboxBacklogReader::class.java) != null)
            assertTrue(context.getBean(OutboxBacklogMetricsPublisher::class.java) != null)
            assertTrue(context.getBean(OperationLogRepository::class.java) != null)
            assertTrue(context.getBean(AgentResultRepository::class.java) != null)
            assertTrue(context.getBean(ConfirmAgentSuggestionService::class.java) != null)
            assertTrue(context.getBean(AgentTaskHandler::class.java) != null)
            assertTrue(context.getBean(AgentTaskOutboxEventHandler::class.java) != null)
            assertTrue(context.getBean(PersistedAgentSuggestionConfirmationService::class.java) != null)
            assertTrue(context.getBean(AgentDoctorChecker::class.java) != null)
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
        .withPropertyValues("fileweft.storage.local-root=${storageRoot.toAbsolutePath()}")

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
    class DatabaseConfiguration {
        @Bean
        fun dataSource(): DataSource = StubDataSource
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
        fun customerDocumentRepository(): DocumentRepository = object : DocumentRepository {
            override fun findById(tenantId: Identifier, documentId: Identifier) = null
            override fun save(document: com.fileweft.domain.document.Document) = Unit
        }
    }

    @Configuration(proxyBeanMethods = false)
    class CustomerDocumentQueryConfiguration {
        @Bean
        fun customerDocumentQueryRepository(): DocumentQueryRepository = object : DocumentQueryRepository {
            override fun findDetail(
                tenantId: Identifier,
                documentId: Identifier,
                folderReadScope: com.fileweft.application.document.DocumentFolderReadScope?,
            ) = null
            override fun findPage(
                tenantId: Identifier,
                request: DocumentPageRequest,
                folderReadScope: com.fileweft.application.document.DocumentFolderReadScope?,
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
                folderReadScope: com.fileweft.application.document.DocumentFolderReadScope?,
            ) = WorkflowTaskPageResult(emptyList())

            override fun findDocumentWorkflowPage(
                tenantId: Identifier,
                documentId: Identifier,
                request: DocumentWorkflowPageRequest,
                folderReadScope: com.fileweft.application.document.DocumentFolderReadScope?,
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
