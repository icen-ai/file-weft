package ai.icen.fw.application.upload

import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.tenant.TenantProvider
import java.io.InputStream
import java.time.Clock
import java.time.Duration

/**
 * Released facade for tenant-isolated durable multipart uploads.
 *
 * Focused internal collaborators own each entry-point family. They share only
 * an internal dependency/safety context; reconciliation remains an explicit collaborator.
 */
class ResumableUploadService @JvmOverloads constructor(
    tenantProvider: TenantProvider,
    userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    storageAdapter: StorageAdapter,
    sessions: ResumableUploadSessionRepository,
    fileObjects: FileObjectRepository,
    fileAssets: FileAssetRepository,
    outbox: OutboxEventRepository,
    identifiers: IdentifierGenerator,
    transaction: ApplicationTransaction,
    clock: Clock,
    sessionTtl: Duration = Duration.ofHours(24),
    metrics: FileWeftMetrics? = null,
) {
    private val context = ResumableUploadContext(
        tenantProvider = tenantProvider,
        userRealmProvider = userRealmProvider,
        authorizationProvider = authorizationProvider,
        storageAdapter = storageAdapter,
        sessions = sessions,
        fileObjects = fileObjects,
        fileAssets = fileAssets,
        outbox = outbox,
        identifiers = identifiers,
        transaction = transaction,
        clock = clock,
        sessionTtl = sessionTtl,
        metrics = metrics,
    )
    private val reconciler = ResumableUploadReconciler(context)
    private val starter = ResumableUploadStarter(context, reconciler)
    private val partHandler = ResumableUploadPartHandler(context)
    private val completionHandler = ResumableUploadCompletionHandler(context, reconciler)
    private val abortHandler = ResumableUploadAbortHandler(context, reconciler)
    private val cleanupService = ResumableUploadCleanupService(context, reconciler)

    fun start(command: StartResumableUploadCommand): ResumableUploadSession = starter.start(command)

    fun startAndInspect(command: StartResumableUploadCommand): ResumableUploadSessionView =
        starter.startAndInspect(command)

    fun startAndInspectWithCallerKey(command: StartResumableUploadCommand): ResumableUploadSessionView =
        starter.startAndInspectWithCallerKey(command)

    fun inspect(sessionId: Identifier): ResumableUploadSessionView = reconciler.inspect(sessionId)

    fun uploadPart(
        sessionId: Identifier,
        partNumber: Int,
        contentLength: Long,
        content: InputStream,
    ): ResumableUploadPart = partHandler.uploadPart(sessionId, partNumber, contentLength, content)

    fun complete(sessionId: Identifier): UploadFileResult = completionHandler.complete(sessionId)

    fun completeAndInspect(sessionId: Identifier): ResumableUploadCompletionResult =
        completionHandler.completeAndInspect(sessionId)

    fun abort(sessionId: Identifier): ResumableUploadSession = abortHandler.abort(sessionId)

    fun abortAndInspect(sessionId: Identifier): ResumableUploadSessionView = abortHandler.abortAndInspect(sessionId)

    @JvmOverloads
    fun cleanupExpired(limit: Int = DEFAULT_CLEANUP_LIMIT): ExpiredResumableUploadCleanupResult =
        cleanupService.cleanupExpired(limit)

    @JvmOverloads
    fun inspectStalledCompletionsAsSystem(
        limit: Int = DEFAULT_CLEANUP_LIMIT,
    ): List<StalledResumableUploadSession> = cleanupService.inspectStalledCompletionsAsSystem(limit)

    @JvmOverloads
    fun inspectStalledCompletions(
        limit: Int = DEFAULT_CLEANUP_LIMIT,
    ): List<StalledResumableUploadSession> = cleanupService.inspectStalledCompletions(limit)

    private companion object {
        const val DEFAULT_CLEANUP_LIMIT = 100
    }
}
