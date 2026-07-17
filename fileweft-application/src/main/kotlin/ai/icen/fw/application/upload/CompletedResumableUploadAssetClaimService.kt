package ai.icen.fw.application.upload

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.document.DocumentDraftService
import ai.icen.fw.application.idempotency.IdempotencyResult
import ai.icen.fw.application.idempotency.IdempotentCommand
import ai.icen.fw.application.idempotency.IdempotentCommandResult
import ai.icen.fw.application.idempotency.IdempotencyReplayMapper
import ai.icen.fw.application.idempotency.RequestIdempotency
import ai.icen.fw.application.idempotency.RequestIdempotencyRepository
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.transaction.ApplicationTransactionBoundary
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentNumberAlreadyExistsException
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import java.time.Clock

/**
 * Atomically consumes completed resumable-upload assets as document content.
 *
 * This service performs no storage or other remote call. Trusted identity and
 * authorization are refreshed before every request, including a replay. The
 * request-idempotency claim, document mutation, exact asset verification and
 * upload consumption marker share one final local transaction.
 */
class CompletedResumableUploadAssetClaimService @JvmOverloads constructor(
    private val tenantProvider: TenantProvider,
    userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val uploadSessions: ResumableUploadSessionRepository,
    private val documents: DocumentRepository,
    private val fileObjects: FileObjectRepository,
    private val fileAssets: FileAssetRepository,
    idempotencyRepository: RequestIdempotencyRepository,
    private val transaction: ApplicationTransaction,
    private val identifiers: IdentifierGenerator,
    private val clock: Clock,
    private val auditTrail: AuditTrail? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)
    private val idempotency = RequestIdempotencyService(
        idempotencyRepository,
        transaction,
        identifiers,
        clock,
    )

    fun createDocument(
        command: CreateDocumentFromCompletedUploadCommand,
    ): CompletedResumableUploadAssetClaimResult {
        ApplicationTransactionBoundary.requireInactive(transaction)
        val request = requestContext(command.uploadId)
        val claims = requireClaimCapability()
        val idempotencyRequest = RequestIdempotency.create(
            tenantId = request.tenantId,
            operatorId = request.user.id,
            idempotencyKey = command.idempotencyKey,
            action = CREATE_FROM_UPLOAD_ACTION,
            resourceType = COMPLETED_UPLOAD_RESOURCE_TYPE,
            resourceId = command.uploadId,
            requestFingerprint = command.fingerprint(),
        )
        val completed = idempotency.findCompleted(idempotencyRequest)
        val authorizedDocumentId = completed?.let(::requireCreateReplayDocumentId) ?: identifiers.nextId()
        authorization.requireDocumentActionAs(
            request.tenantId,
            authorizedDocumentId,
            DocumentDraftService.CREATE_ACTION,
            request.user,
        )
        return try {
            executeCreateDocument(
                command,
                request,
                claims,
                idempotencyRequest,
                authorizedDocumentId,
                refreshAuthorizationOnReplayMismatch = completed == null,
            )
        } catch (refresh: CreateReplayAuthorizationRefreshRequiredException) {
            // The key was absent during the pre-authorization read, but an
            // equal concurrent request committed before execute acquired it.
            // Refresh authorization for that exact durable document before a
            // single replay attempt; no provider call occurs in the transaction.
            authorization.requireDocumentActionAs(
                request.tenantId,
                refresh.documentId,
                DocumentDraftService.CREATE_ACTION,
                request.user,
            )
            executeCreateDocument(
                command,
                request,
                claims,
                idempotencyRequest,
                refresh.documentId,
                refreshAuthorizationOnReplayMismatch = false,
            )
        }
    }

    private fun executeCreateDocument(
        command: CreateDocumentFromCompletedUploadCommand,
        request: ClaimRequestContext,
        claims: CompletedResumableUploadAssetClaimRepository,
        idempotencyRequest: RequestIdempotency,
        authorizedDocumentId: Identifier,
        refreshAuthorizationOnReplayMismatch: Boolean,
    ): CompletedResumableUploadAssetClaimResult {
        val execution = idempotency.execute(
            idempotencyRequest,
            replayMapper(
                uploadId = command.uploadId,
                request = request,
                claims = claims,
                idempotencyRequest = idempotencyRequest,
                expectedDocumentId = authorizedDocumentId,
                requireDocumentAssetBinding = true,
                refreshCreateAuthorizationOnMismatch = refreshAuthorizationOnReplayMismatch,
            ),
            IdempotentCommand {
                createDocumentClaim(
                    command,
                    request,
                    claims,
                    idempotencyRequest,
                    authorizedDocumentId,
                )
            },
        )
        return execution.value.withReplay(execution.replayed)
    }

    fun addDocumentVersion(
        command: AddDocumentVersionFromCompletedUploadCommand,
    ): CompletedResumableUploadAssetClaimResult {
        ApplicationTransactionBoundary.requireInactive(transaction)
        val request = requestContext(command.uploadId)
        // Target authorization is deliberately refreshed before idempotency
        // replay, so a revoked editor cannot recover the earlier result.
        authorization.requireDocumentActionAs(
            request.tenantId,
            command.documentId,
            DocumentDraftService.EDIT_ACTION,
            request.user,
        )
        val claims = requireClaimCapability()
        val idempotencyRequest = RequestIdempotency.create(
            tenantId = request.tenantId,
            operatorId = request.user.id,
            idempotencyKey = command.idempotencyKey,
            action = ADD_VERSION_FROM_UPLOAD_ACTION,
            resourceType = COMPLETED_UPLOAD_RESOURCE_TYPE,
            resourceId = command.uploadId,
            subresourceId = command.documentId,
            requestFingerprint = command.fingerprint(),
        )
        val execution = idempotency.execute(
            idempotencyRequest,
            replayMapper(
                uploadId = command.uploadId,
                request = request,
                claims = claims,
                idempotencyRequest = idempotencyRequest,
                expectedDocumentId = command.documentId,
                requireDocumentAssetBinding = false,
            ),
            IdempotentCommand {
                addVersionClaim(command, request, claims, idempotencyRequest)
            },
        )
        return execution.value.withReplay(execution.replayed)
    }

    private fun createDocumentClaim(
        command: CreateDocumentFromCompletedUploadCommand,
        request: ClaimRequestContext,
        claims: CompletedResumableUploadAssetClaimRepository,
        idempotencyRequest: RequestIdempotency,
        documentId: Identifier,
    ): IdempotentCommandResult<CompletedResumableUploadAssetClaimResult> {
        val asset = requiredFreshAsset(command.uploadId, request, claims)
        if (documents.findByDocumentNumber(request.tenantId, command.documentNumber) != null) {
            throw DocumentNumberAlreadyExistsException(command.documentNumber)
        }
        val versionId = identifiers.nextId()
        val version = DocumentVersion(
            id = versionId,
            tenantId = request.tenantId,
            documentId = documentId,
            versionNumber = DocumentDraftService.INITIAL_VERSION,
            fileObjectId = asset.fileObject.id,
        )
        val document = Document(
            id = documentId,
            tenantId = request.tenantId,
            assetId = asset.fileAsset.id,
            documentNumber = command.documentNumber,
            title = command.title,
            versions = listOf(version),
            currentVersionId = version.id,
        )
        documents.save(document)
        val claim = newClaim(asset, document.id, version.id, request, idempotencyRequest)
        requireMarkedClaim(claims, asset.session, claim)
        auditTrail?.record(
            tenantId = request.tenantId,
            resourceType = DOCUMENT_RESOURCE_TYPE,
            resourceId = document.id,
            action = CREATE_FROM_UPLOAD_ACTION,
            operatorId = request.user.id,
            operatorName = request.user.displayName,
            details = linkedMapOf(
                "uploadId" to command.uploadId.value,
                "fileAssetId" to asset.fileAsset.id.value,
                "versionId" to version.id.value,
            ),
        )
        val result = claim.toResult(replayed = false)
        return IdempotentCommandResult(
            value = result,
            idempotencyResult = IdempotencyResult(
                DOCUMENT_RESOURCE_TYPE,
                document.id,
                DOCUMENT_VERSION_RESOURCE_TYPE,
                version.id,
            ),
        )
    }

    private fun addVersionClaim(
        command: AddDocumentVersionFromCompletedUploadCommand,
        request: ClaimRequestContext,
        claims: CompletedResumableUploadAssetClaimRepository,
        idempotencyRequest: RequestIdempotency,
    ): IdempotentCommandResult<CompletedResumableUploadAssetClaimResult> {
        val asset = requiredFreshAsset(command.uploadId, request, claims)
        val document = documents.findForMutation(request.tenantId, command.documentId)
            ?.takeIf { candidate ->
                candidate.tenantId == request.tenantId && candidate.id == command.documentId
            }
            ?: throw DocumentNotFoundException(command.documentId)
        val version = DocumentVersion(
            id = identifiers.nextId(),
            tenantId = request.tenantId,
            documentId = document.id,
            versionNumber = command.versionNumber,
            fileObjectId = asset.fileObject.id,
        )
        document.addVersion(version)
        documents.save(document)
        val claim = newClaim(asset, document.id, version.id, request, idempotencyRequest)
        requireMarkedClaim(claims, asset.session, claim)
        auditTrail?.record(
            tenantId = request.tenantId,
            resourceType = DOCUMENT_RESOURCE_TYPE,
            resourceId = document.id,
            action = ADD_VERSION_FROM_UPLOAD_ACTION,
            operatorId = request.user.id,
            operatorName = request.user.displayName,
            details = linkedMapOf(
                "uploadId" to command.uploadId.value,
                "fileAssetId" to asset.fileAsset.id.value,
                "versionId" to version.id.value,
                "version" to version.versionNumber,
            ),
        )
        val result = claim.toResult(replayed = false)
        return IdempotentCommandResult(
            value = result,
            idempotencyResult = IdempotencyResult(
                DOCUMENT_RESOURCE_TYPE,
                document.id,
                DOCUMENT_VERSION_RESOURCE_TYPE,
                version.id,
            ),
        )
    }

    private fun requiredFreshAsset(
        uploadId: Identifier,
        request: ClaimRequestContext,
        claims: CompletedResumableUploadAssetClaimRepository,
    ): VerifiedCompletedAsset {
        val state = claims.lockCompletedAssetClaim(request.tenantId, request.ownerId, uploadId)
            ?: throw CompletedResumableUploadAssetNotFoundException(uploadId)
        val session = state.session
        requireOwnerBinding(session, request, uploadId)
        if (state.claim != null) throw CompletedResumableUploadAssetClaimConflictException()
        val claimTime = nonNegativeNow()
        val completedAt = session.completedAt
        if (
            session.status != ResumableUploadSessionStatus.COMPLETED ||
            completedAt == null ||
            session.expiresAt <= claimTime ||
            session.assetType != COMPLETED_UPLOAD_DOCUMENT_ASSET_TYPE ||
            session.lastError != null
        ) {
            throw CompletedResumableUploadAssetClaimStateException(
                "The upload is not an unexpired completed DOCUMENT content asset.",
            )
        }
        if (session.updatedTime != completedAt) {
            throw CompletedResumableUploadAssetClaimStateException(
                "The completed upload timestamps are inconsistent.",
            )
        }
        if (claimTime < completedAt || claimTime < session.updatedTime) {
            throw CompletedResumableUploadAssetClaimStateException(
                "The system clock precedes the completed upload state.",
            )
        }
        val fileObject = fileObjects.findById(request.tenantId, session.fileObjectId)
            ?: throw CompletedResumableUploadAssetClaimStateException("The completed upload file object is missing.")
        val fileAsset = fileAssets.findById(request.tenantId, session.fileAssetId)
            ?: throw CompletedResumableUploadAssetClaimStateException("The completed upload file asset is missing.")
        requireExactFileBinding(session, fileObject, fileAsset)
        return VerifiedCompletedAsset(session, fileObject, fileAsset, claimTime)
    }

    private fun replayMapper(
        uploadId: Identifier,
        request: ClaimRequestContext,
        claims: CompletedResumableUploadAssetClaimRepository,
        idempotencyRequest: RequestIdempotency,
        expectedDocumentId: Identifier,
        requireDocumentAssetBinding: Boolean,
        refreshCreateAuthorizationOnMismatch: Boolean = false,
    ): IdempotencyReplayMapper<CompletedResumableUploadAssetClaimResult> = IdempotencyReplayMapper { result ->
        if (
            result.resourceType != DOCUMENT_RESOURCE_TYPE ||
            result.relatedResourceType != DOCUMENT_VERSION_RESOURCE_TYPE ||
            result.relatedResourceId == null
        ) {
            throw CompletedResumableUploadAssetClaimStateException("The completed upload replay result is invalid.")
        }
        if (result.resourceId != expectedDocumentId) {
            if (refreshCreateAuthorizationOnMismatch) {
                throw CreateReplayAuthorizationRefreshRequiredException(result.resourceId)
            }
            throw CompletedResumableUploadAssetClaimStateException(
                "The completed upload replay targets a different document.",
            )
        }
        val state = claims.findCompletedAssetClaim(request.tenantId, request.ownerId, uploadId)
            ?: throw CompletedResumableUploadAssetClaimStateException("The completed upload claim marker is missing.")
        requireOwnerBinding(state.session, request, uploadId)
        val claim = state.claim
            ?: throw CompletedResumableUploadAssetClaimStateException("The completed upload claim marker is missing.")
        if (
            claim.idempotencyKeyDigest != idempotencyRequest.keyDigest ||
            claim.resourceType != result.resourceType ||
            claim.resourceId != result.resourceId ||
            claim.subresourceId != result.relatedResourceId ||
            claim.claimedBy != request.ownerId ||
            claim.claimedTime >= state.session.expiresAt
        ) {
            throw CompletedResumableUploadAssetClaimStateException(
                "The completed upload claim does not match its idempotent result.",
            )
        }
        val fileObject = fileObjects.findById(request.tenantId, state.session.fileObjectId)
            ?: throw CompletedResumableUploadAssetClaimStateException("The claimed upload file object is missing.")
        val fileAsset = fileAssets.findById(request.tenantId, state.session.fileAssetId)
            ?: throw CompletedResumableUploadAssetClaimStateException("The claimed upload file asset is missing.")
        requireExactFileBinding(state.session, fileObject, fileAsset)
        val document = documents.findById(request.tenantId, claim.resourceId)
            ?: throw CompletedResumableUploadAssetClaimStateException("The claimed document is missing.")
        if (document.tenantId != request.tenantId || document.id != claim.resourceId) {
            throw CompletedResumableUploadAssetClaimStateException(
                "The claimed document is outside its persisted tenant or resource scope.",
            )
        }
        val version = document.versions.singleOrNull { candidate -> candidate.id == claim.subresourceId }
            ?: throw CompletedResumableUploadAssetClaimStateException("The claimed document version is missing.")
        if (
            version.tenantId != request.tenantId ||
            version.documentId != document.id ||
            version.fileObjectId != state.session.fileObjectId ||
            (requireDocumentAssetBinding && document.assetId != state.session.fileAssetId)
        ) {
            throw CompletedResumableUploadAssetClaimStateException(
                "The claimed document no longer exactly matches the completed upload.",
            )
        }
        claim.toResult(replayed = true)
    }

    private fun newClaim(
        asset: VerifiedCompletedAsset,
        documentId: Identifier,
        versionId: Identifier,
        request: ClaimRequestContext,
        idempotencyRequest: RequestIdempotency,
    ): CompletedResumableUploadAssetClaim = CompletedResumableUploadAssetClaim(
        tenantId = request.tenantId,
        uploadId = asset.session.id,
        fileObjectId = asset.session.fileObjectId,
        fileAssetId = asset.session.fileAssetId,
        idempotencyKeyDigest = idempotencyRequest.keyDigest,
        resourceType = DOCUMENT_RESOURCE_TYPE,
        resourceId = documentId,
        subresourceId = versionId,
        claimedBy = request.ownerId,
        claimedTime = asset.claimTime,
    )

    private fun requireMarkedClaim(
        claims: CompletedResumableUploadAssetClaimRepository,
        session: ResumableUploadSession,
        claim: CompletedResumableUploadAssetClaim,
    ) {
        val marked = claims.markCompletedAssetClaimed(session, claim)
            ?: throw CompletedResumableUploadAssetClaimConflictException()
        if (!sameClaimedSession(marked.session, session, claim.claimedTime) || !sameClaim(marked.claim, claim)) {
            throw CompletedResumableUploadAssetClaimStateException(
                "The resumable upload repository returned an invalid consumption marker.",
            )
        }
    }

    private fun requestContext(uploadId: Identifier): ClaimRequestContext {
        val tenantId = tenantProvider.currentTenant().tenantId
        val user = authorization.requireCurrentUser()
        authorization.requireActionAs(
            tenantId,
            uploadId,
            COMPLETED_UPLOAD_RESOURCE_TYPE,
            COMPLETED_UPLOAD_CLAIM_ACTION,
            user,
        )
        return ClaimRequestContext(tenantId, user, user.id.value)
    }

    private fun requireCreateReplayDocumentId(result: IdempotencyResult): Identifier {
        if (
            result.resourceType != DOCUMENT_RESOURCE_TYPE ||
            result.relatedResourceType != DOCUMENT_VERSION_RESOURCE_TYPE ||
            result.relatedResourceId == null
        ) {
            throw CompletedResumableUploadAssetClaimStateException(
                "The completed upload create replay result is invalid.",
            )
        }
        return result.resourceId
    }

    private fun requireClaimCapability(): CompletedResumableUploadAssetClaimRepository =
        uploadSessions as? CompletedResumableUploadAssetClaimRepository
            ?: throw CompletedResumableUploadAssetClaimUnavailableException()

    private fun requireOwnerBinding(
        session: ResumableUploadSession,
        request: ClaimRequestContext,
        uploadId: Identifier,
    ) {
        if (
            session.tenantId != request.tenantId ||
            session.id != uploadId ||
            session.ownerId != request.ownerId
        ) {
            throw CompletedResumableUploadAssetNotFoundException(uploadId)
        }
    }

    private fun requireExactFileBinding(
        session: ResumableUploadSession,
        fileObject: FileObject,
        fileAsset: FileAsset,
    ) {
        val exactObject =
            fileObject.id == session.fileObjectId &&
                fileObject.tenantId == session.tenantId &&
                fileObject.fileName == session.fileName &&
                fileObject.contentLength == session.contentLength &&
                fileObject.storageType == session.storageLocation.storageType &&
                fileObject.storagePath == session.storageLocation.path &&
                fileObject.contentType == session.contentType &&
                (session.expectedContentHash == null || fileObject.contentHash == session.expectedContentHash)
        val exactAsset =
            fileAsset.id == session.fileAssetId &&
                fileAsset.tenantId == session.tenantId &&
                fileAsset.fileObjectId == session.fileObjectId &&
                fileAsset.assetType == session.assetType &&
                fileAsset.metadata == session.metadata
        if (!exactObject || !exactAsset) {
            throw CompletedResumableUploadAssetClaimStateException(
                "The completed upload does not exactly match its persisted file object and asset.",
            )
        }
    }

    private fun sameClaimedSession(
        first: ResumableUploadSession,
        second: ResumableUploadSession,
        claimedTime: Long,
    ): Boolean =
        first.id == second.id &&
            first.tenantId == second.tenantId &&
            first.ownerId == second.ownerId &&
            first.idempotencyKey == second.idempotencyKey &&
            first.storageUploadId == second.storageUploadId &&
            first.storageLocation == second.storageLocation &&
            first.fileObjectId == second.fileObjectId &&
            first.fileAssetId == second.fileAssetId &&
            first.fileName == second.fileName &&
            first.contentLength == second.contentLength &&
            first.assetType == second.assetType &&
            first.contentType == second.contentType &&
            first.expectedContentHash == second.expectedContentHash &&
            first.metadata == second.metadata &&
            first.status == second.status &&
            first.expiresAt == second.expiresAt &&
            first.lastError == second.lastError &&
            first.completedAt == second.completedAt &&
            first.createdTime == second.createdTime &&
            first.updatedTime == claimedTime

    private fun sameClaim(
        first: CompletedResumableUploadAssetClaim?,
        second: CompletedResumableUploadAssetClaim,
    ): Boolean = first != null &&
        first.tenantId == second.tenantId &&
        first.uploadId == second.uploadId &&
        first.fileObjectId == second.fileObjectId &&
        first.fileAssetId == second.fileAssetId &&
        first.idempotencyKeyDigest == second.idempotencyKeyDigest &&
        first.resourceType == second.resourceType &&
        first.resourceId == second.resourceId &&
        first.subresourceId == second.subresourceId &&
        first.claimedBy == second.claimedBy &&
        first.claimedTime == second.claimedTime

    private fun CompletedResumableUploadAssetClaim.toResult(
        replayed: Boolean,
    ): CompletedResumableUploadAssetClaimResult = CompletedResumableUploadAssetClaimResult(
        uploadId = uploadId,
        fileObjectId = fileObjectId,
        fileAssetId = fileAssetId,
        documentId = resourceId,
        versionId = subresourceId,
        replayed = replayed,
    )

    private fun CompletedResumableUploadAssetClaimResult.withReplay(
        replayed: Boolean,
    ): CompletedResumableUploadAssetClaimResult = CompletedResumableUploadAssetClaimResult(
        uploadId = uploadId,
        fileObjectId = fileObjectId,
        fileAssetId = fileAssetId,
        documentId = documentId,
        versionId = versionId,
        replayed = replayed,
    )

    private fun nonNegativeNow(): Long = clock.millis().also { now ->
        if (now < 0) throw CompletedResumableUploadAssetClaimStateException("System clock returned an invalid time.")
    }

    private class ClaimRequestContext(
        val tenantId: Identifier,
        val user: UserIdentity,
        val ownerId: String,
    )

    private class VerifiedCompletedAsset(
        val session: ResumableUploadSession,
        val fileObject: FileObject,
        val fileAsset: FileAsset,
        val claimTime: Long,
    )

    private class CreateReplayAuthorizationRefreshRequiredException(
        val documentId: Identifier,
    ) : RuntimeException()
}
