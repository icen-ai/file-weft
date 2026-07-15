package ai.icen.fw.starter.boot2

import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.idempotency.RequestIdempotencyRepository
import ai.icen.fw.application.upload.CompletedPresignedUploadAssetClaimService
import ai.icen.fw.application.upload.CompletedPresignedUploadAssetClaimRepository
import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaimService
import ai.icen.fw.application.upload.PresignedUploadCleanupService
import ai.icen.fw.application.upload.PresignedUploadDiagnosticsService
import ai.icen.fw.application.upload.PresignedUploadDiagnosticsSnapshot
import ai.icen.fw.application.upload.PresignedUploadRecoveryService
import ai.icen.fw.application.upload.PresignedUploadService
import ai.icen.fw.application.upload.PresignedUploadSessionRepository
import ai.icen.fw.application.upload.ResumableUploadService
import ai.icen.fw.application.upload.ResumableUploadSessionRepository
import ai.icen.fw.application.upload.UploadApplicationService
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.plugin.FileWeftPlugin
import ai.icen.fw.spi.storage.PresignedUploadStorageAdapter
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.tenant.TenantProvider
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.withSettings
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock
import javax.sql.DataSource

class PresignedUploadAutoConfigurationTest {
    @Test
    fun `wires the durable service and worker capabilities only when explicitly enabled`() {
        runner(presignedStorage()).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(PresignedUploadService::class.java)
            assertThat(context).hasSingleBean(PresignedUploadRecoveryService::class.java)
            assertThat(context).hasSingleBean(PresignedUploadCleanupService::class.java)
            assertThat(context).hasSingleBean(CompletedPresignedUploadAssetClaimService::class.java)
            assertThat(context).hasSingleBean(PresignedUploadDiagnosticsService::class.java)
            assertThat(context.getBean(PresignedUploadSessionRepository::class.java))
                .isInstanceOf(CompletedPresignedUploadAssetClaimRepository::class.java)
            val doctorPlugin = context.getBeansOfType(FileWeftPlugin::class.java).values.single()
            assertThat(doctorPlugin.id()).isEqualTo("flowweft-presigned-upload-doctor")
            assertThat(doctorPlugin.doctorCheckers().single().name()).isEqualTo("presigned-upload")
        }
    }

    @Test
    fun `keeps every presigned component absent by default`() {
        runner(presignedStorage(), enabled = false).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(PresignedUploadService::class.java)
            assertThat(context).doesNotHaveBean(PresignedUploadRecoveryService::class.java)
            assertThat(context).doesNotHaveBean(PresignedUploadCleanupService::class.java)
            assertThat(context).doesNotHaveBean(CompletedPresignedUploadAssetClaimService::class.java)
            assertThat(context).doesNotHaveBean(PresignedUploadDiagnosticsService::class.java)
            assertThat(context).doesNotHaveBean("flowWeftPresignedUploadDoctorPlugin")
        }
    }

    @Test
    fun `does not advertise atomic finalization for a host repository without the claim capability`() {
        val incomplete = Mockito.mock(PresignedUploadSessionRepository::class.java)
        runner(presignedStorage())
            .withBean(PresignedUploadSessionRepository::class.java, { incomplete })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(PresignedUploadService::class.java)
                assertThat(context).doesNotHaveBean(CompletedPresignedUploadAssetClaimService::class.java)
            }
    }

    @Test
    fun `exposes completed document claims only for a claim-capable resumable repository`() {
        val documents = Mockito.mock(DocumentRepository::class.java)
        runner(presignedStorage(), enabled = false)
            .withBean(DocumentRepository::class.java, { documents })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(CompletedResumableUploadAssetClaimService::class.java)
            }

        runner(presignedStorage(), enabled = false)
            .withBean(DocumentRepository::class.java, { documents })
            .withBean(
                ResumableUploadSessionRepository::class.java,
                { Mockito.mock(ResumableUploadSessionRepository::class.java) },
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(CompletedResumableUploadAssetClaimService::class.java)
            }
    }

    @Test
    fun `publishes global low-cardinality gauges and fails closed before or after observation failure`() {
        val diagnostics = Mockito.mock(PresignedUploadDiagnosticsService::class.java)
        val registry = SimpleMeterRegistry()
        Mockito.`when`(diagnostics.inspectGlobal()).thenReturn(
            PresignedUploadDiagnosticsSnapshot(2, 3, 5, 7, 11, 13),
        )

        runner(presignedStorage())
            .withPropertyValues("fileweft.worker.enabled=true")
            .withBean(PresignedUploadDiagnosticsService::class.java, { diagnostics })
            .withBean(MeterRegistry::class.java, { registry })
            .run { context ->
                val publisher = context.getBean(FlowWeftPresignedUploadMetricsPublisher::class.java)
                assertThat(gauge(registry, "flowweft.presigned_upload.observation_failure")).isEqualTo(1.0)

                publisher.publish()

                assertThat(gauge(registry, "flowweft.presigned_upload.active")).isEqualTo(2.0)
                assertThat(gauge(registry, "flowweft.presigned_upload.stuck_claims")).isEqualTo(3.0)
                assertThat(gauge(registry, "flowweft.presigned_upload.cleanup_due")).isEqualTo(5.0)
                assertThat(gauge(registry, "flowweft.presigned_upload.cleanup_failures")).isEqualTo(7.0)
                assertThat(gauge(registry, "flowweft.presigned_upload.orphan_risk")).isEqualTo(11.0)
                assertThat(gauge(registry, "flowweft.presigned_upload.oldest_maintenance_age_seconds"))
                    .isEqualTo(13.0)
                assertThat(gauge(registry, "flowweft.presigned_upload.observation_failure")).isEqualTo(0.0)

                Mockito.`when`(diagnostics.inspectGlobal()).thenThrow(IllegalStateException("private JDBC detail"))
                publisher.publish()

                assertThat(gauge(registry, "flowweft.presigned_upload.observation_failure")).isEqualTo(1.0)
                assertThat(gauge(registry, "flowweft.presigned_upload.active")).isEqualTo(2.0)
            }
    }

    @Test
    fun `fails closed when selected storage lacks direct upload capability`() {
        runner(Mockito.mock(StorageAdapter::class.java)).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasRootCauseMessage(
                "The selected StorageAdapter does not provide the PresignedUploadStorageAdapter capability.",
            )
        }
    }

    @Test
    fun `fails closed instead of selecting among multiple storage candidates`() {
        runner(presignedStorage())
            .withBean("secondStorage", StorageAdapter::class.java, { presignedStorage() })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining(
                    "Presigned uploads require exactly one selected StorageAdapter",
                )
            }
    }

    private fun runner(storage: StorageAdapter, enabled: Boolean = true): ApplicationContextRunner =
        ApplicationContextRunner()
        .withUserConfiguration(FileWeftUploadConfiguration::class.java)
        .withPropertyValues("fileweft.upload.presigned-enabled=$enabled")
        .withBean(DataSource::class.java, { Mockito.mock(DataSource::class.java) })
        .withBean(ObjectMapper::class.java, { ObjectMapper() })
        .withBean(FileWeftProperties::class.java, { FileWeftProperties() })
        .withBean(TenantProvider::class.java, { Mockito.mock(TenantProvider::class.java) })
        .withBean(UserRealmProvider::class.java, { Mockito.mock(UserRealmProvider::class.java) })
        .withBean(AuthorizationProvider::class.java, { Mockito.mock(AuthorizationProvider::class.java) })
        .withBean(IdentifierGenerator::class.java, { Mockito.mock(IdentifierGenerator::class.java) })
        .withBean(ApplicationTransaction::class.java, { Mockito.mock(ApplicationTransaction::class.java) })
        .withBean(RequestIdempotencyRepository::class.java, { Mockito.mock(RequestIdempotencyRepository::class.java) })
        .withBean(FileObjectRepository::class.java, { Mockito.mock(FileObjectRepository::class.java) })
        .withBean(FileAssetRepository::class.java, { Mockito.mock(FileAssetRepository::class.java) })
        .withBean(Clock::class.java, { Clock.systemUTC() })
        .withBean(StorageAdapter::class.java, { storage })
        .withBean(UploadApplicationService::class.java, { Mockito.mock(UploadApplicationService::class.java) })
        .withBean(ResumableUploadService::class.java, { Mockito.mock(ResumableUploadService::class.java) })

    private fun gauge(registry: MeterRegistry, name: String): Double = registry.get(name).gauge().value()

    private fun presignedStorage(): StorageAdapter = Mockito.mock(
        StorageAdapter::class.java,
        withSettings().extraInterfaces(PresignedUploadStorageAdapter::class.java),
    )
}
