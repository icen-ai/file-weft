package ai.icen.fw.application.upload

import ai.icen.fw.application.idempotency.IdempotencyResult
import ai.icen.fw.application.idempotency.IdempotencyReplayMapper
import ai.icen.fw.application.idempotency.IdempotentCommand
import ai.icen.fw.application.idempotency.IdempotentCommandResult
import ai.icen.fw.application.idempotency.RequestIdempotency
import ai.icen.fw.application.idempotency.RequestIdempotencyRepository
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.transaction.ApplicationTransactionBoundary
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
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
 * Durable saga boundary for direct upload finalization.
 *
 * Provider verification is completed outside the database transaction by
 * [PresignedUploadService]. The resulting immutable evidence is then reloaded,
 * row-locked and claimed together with FileObject, FileAsset and request
 * idempotency state in one local transaction.
 */
class CompletedPresignedUploadAssetClaimService(
    private val tenantProvider: TenantProvider,
    userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val uploads: PresignedUploadService,
    private val uploadSessions: PresignedUploadSessionRepository,
    private val fileObjects: FileObjectRepository,
    private val fileAssets: FileAssetRepository,
    idempotencyRepository: RequestIdempotencyRepository,
    private val transaction: ApplicationTransaction,
    private val identifiers: IdentifierGenerator,
    private val clock: Clock,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)
    private val idempotency = RequestIdempotencyService(idempotencyRepository, transaction, identifiers, clock)

    fun finalizeUpload(command: CompletePresignedUploadAssetCommand): CompletedPresignedUploadAssetClaimResult {
        ApplicationTransactionBoundary.requireInactive(transaction)
        val initial = requestContext(command.uploadId)
        val completion = uploads.complete(CompletePresignedUploadCommand(command.uploadId))
        if (completion.sessionId != command.uploadId) {
            throw CompletedPresignedUploadAssetClaimStateException(
                "Provider completion returned a different presigned upload id.",
            )
        }
        val fresh = requestContext(command.uploadId)
        if (fresh.tenantId != initial.tenantId || fresh.user.id != initial.user.id) {
            throw CompletedPresignedUploadAssetNotFoundException(command.uploadId)
        }
        val claims = requireClaimCapability()
        val request = RequestIdempotency.create(
            tenantId = fresh.tenantId,
            operatorId = fresh.user.id,
            idempotencyKey = command.idempotencyKey,
            action = PRESIGNED_UPLOAD_FINALIZE_ACTION,
            resourceType = PRESIGNED_UPLOAD_CLAIM_RESOURCE_TYPE,
            resourceId = command.uploadId,
            requestFingerprint = command.fingerprint(),
        )
        val execution = idempotency.execute(
            request,
            replayMapper(command.uploadId, fresh, claims, request),
            IdempotentCommand { claim(command.uploadId, fresh, claims, request) },
        )
        return execution.value.withReplay(execution.replayed)
    }

    private fun claim(
        uploadId: Identifier,
        request: ClaimRequestContext,
        claims: CompletedPresignedUploadAssetClaimRepository,
        idempotencyRequest: RequestIdempotency,
    ): IdempotentCommandResult<CompletedPresignedUploadAssetClaimResult> {
        val state = claims.lockCompletedAssetClaim(request.tenantId, request.ownerId, uploadId)
            ?: throw CompletedPresignedUploadAssetNotFoundException(uploadId)
        requireOwnerAndVerifiedCompletion(state.session, request, uploadId)
        if (state.claim != null) throw CompletedPresignedUploadAssetClaimConflictException()
        val claimTime = nonNegativeNow()
        val completedTime = state.session.completedTime
            ?: throw CompletedPresignedUploadAssetClaimStateException("Completed upload time is missing.")
        if (claimTime < completedTime || claimTime >= state.session.sessionExpiresAt) {
            throw CompletedPresignedUploadAssetClaimStateException(
                "The completed presigned upload is outside its asset-claim lifetime.",
            )
        }
        val finalization = state.session.finalization
            ?: throw CompletedPresignedUploadAssetClaimStateException("Provider finalization evidence is missing.")
        val fileObjectId = identifiers.nextId()
        val fileAssetId = identifiers.nextId()
        if (
            fileObjects.findById(request.tenantId, fileObjectId) != null ||
            fileAssets.findById(request.tenantId, fileAssetId) != null
        ) {
            throw CompletedPresignedUploadAssetClaimStateException("Generated content asset identifiers already exist.")
        }
        val fileObject = FileObject(
            id = fileObjectId,
            tenantId = request.tenantId,
            fileName = state.session.fileName,
            contentLength = state.session.contentLength,
            storageType = finalization.storedObject.location.storageType,
            storagePath = finalization.storedObject.location.path,
            contentType = finalization.storedObject.contentType,
            contentHash = finalization.storedObject.contentHash,
        )
        val fileAsset = FileAsset(
            id = fileAssetId,
            tenantId = request.tenantId,
            fileObjectId = fileObject.id,
            assetType = PRESIGNED_UPLOAD_ASSET_TYPE,
            metadata = state.session.metadata,
        )
        fileObjects.save(fileObject)
        fileAssets.save(fileAsset)
        val claim = CompletedPresignedUploadAssetClaim(
            tenantId = request.tenantId,
            uploadId = uploadId,
            fileObjectId = fileObject.id,
            fileAssetId = fileAsset.id,
            idempotencyKeyDigest = idempotencyRequest.keyDigest,
            purpose = PRESIGNED_UPLOAD_ASSET_PURPOSE,
            claimedBy = request.ownerId,
            claimedTime = claimTime,
        )
        val marked = claims.markCompletedAssetClaimed(state.session, claim)
            ?: throw CompletedPresignedUploadAssetClaimConflictException()
        requireExactClaimedState(marked, state.session, claim, fileObject, fileAsset)
        val result = claim.toResult(replayed = false)
        return IdempotentCommandResult(
            result,
            IdempotencyResult(FILE_OBJECT_RESOURCE_TYPE, fileObject.id, FILE_ASSET_RESOURCE_TYPE, fileAsset.id),
        )
    }

    private fun replayMapper(
        uploadId: Identifier,
        request: ClaimRequestContext,
        claims: CompletedPresignedUploadAssetClaimRepository,
        idempotencyRequest: RequestIdempotency,
    ): IdempotencyReplayMapper<CompletedPresignedUploadAssetClaimResult> = IdempotencyReplayMapper { result ->
        if (
            result.resourceType != FILE_OBJECT_RESOURCE_TYPE ||
            result.relatedResourceType != FILE_ASSET_RESOURCE_TYPE ||
            result.relatedResourceId == null
        ) {
            throw CompletedPresignedUploadAssetClaimStateException("Presigned upload replay result is invalid.")
        }
        val state = claims.findCompletedAssetClaim(request.tenantId, request.ownerId, uploadId)
            ?: throw CompletedPresignedUploadAssetClaimStateException("Presigned upload claim marker is missing.")
        requireOwnerAndVerifiedCompletion(state.session, request, uploadId)
        val claim = state.claim
            ?: throw CompletedPresignedUploadAssetClaimStateException("Presigned upload claim marker is missing.")
        if (
            claim.fileObjectId != result.resourceId ||
            claim.fileAssetId != result.relatedResourceId ||
            claim.idempotencyKeyDigest != idempotencyRequest.keyDigest ||
            claim.claimedBy != request.ownerId ||
            claim.purpose != PRESIGNED_UPLOAD_ASSET_PURPOSE
        ) {
            throw CompletedPresignedUploadAssetClaimStateException(
                "Presigned upload claim does not match its idempotent result.",
            )
        }
        val fileObject = fileObjects.findById(request.tenantId, claim.fileObjectId)
            ?: throw CompletedPresignedUploadAssetClaimStateException("Claimed file object is missing.")
        val fileAsset = fileAssets.findById(request.tenantId, claim.fileAssetId)
            ?: throw CompletedPresignedUploadAssetClaimStateException("Claimed file asset is missing.")
        requireExactContentBinding(state.session, claim, fileObject, fileAsset)
        claim.toResult(replayed = true)
    }

    private fun requestContext(uploadId: Identifier): ClaimRequestContext {
        val tenantId = tenantProvider.currentTenant().tenantId
        val user = authorization.requireCurrentUser()
        authorization.requireActionAs(
            tenantId,
            uploadId,
            PRESIGNED_UPLOAD_CLAIM_RESOURCE_TYPE,
            PRESIGNED_UPLOAD_CLAIM_ACTION,
            user,
        )
        return ClaimRequestContext(tenantId, user, user.id.value)
    }

    private fun requireOwnerAndVerifiedCompletion(
        session: PresignedUploadSession,
        request: ClaimRequestContext,
        uploadId: Identifier,
    ) {
        if (session.tenantId != request.tenantId || session.id != uploadId || session.ownerId != request.ownerId) {
            throw CompletedPresignedUploadAssetNotFoundException(uploadId)
        }
        val finalization = session.finalization
        if (
            session.status != PresignedUploadSessionStatus.COMPLETED ||
            finalization == null ||
            session.completedTime == null ||
            session.lastError != null ||
            finalization.tenantId != session.tenantId ||
            finalization.bindingId != session.id ||
            finalization.sourceLocation != session.stagingLocation ||
            finalization.storedObject.contentLength != session.contentLength ||
            finalization.storedObject.contentType != session.contentType ||
            finalization.storedObject.contentHash != session.contentHash ||
            finalization.checksum != session.checksum ||
            finalization.metadata != session.metadata
        ) {
            throw CompletedPresignedUploadAssetClaimStateException(
                "Presigned upload provider evidence is not an exact verified completion.",
            )
        }
    }

    private fun requireExactClaimedState(
        marked: CompletedPresignedUploadAssetClaimState,
        expected: PresignedUploadSession,
        claim: CompletedPresignedUploadAssetClaim,
        fileObject: FileObject,
        fileAsset: FileAsset,
    ) {
        if (
            marked.session.version != Math.addExact(expected.version, 1) ||
            marked.session.id != expected.id ||
            marked.session.tenantId != expected.tenantId ||
            marked.session.finalization?.revision != expected.finalization?.revision ||
            !sameClaim(marked.claim, claim)
        ) {
            throw CompletedPresignedUploadAssetClaimStateException("Repository returned an invalid asset claim marker.")
        }
        requireExactContentBinding(marked.session, claim, fileObject, fileAsset)
    }

    private fun requireExactContentBinding(
        session: PresignedUploadSession,
        claim: CompletedPresignedUploadAssetClaim,
        fileObject: FileObject,
        fileAsset: FileAsset,
    ) {
        val finalization = session.finalization
            ?: throw CompletedPresignedUploadAssetClaimStateException("Provider finalization evidence is missing.")
        val exactObject =
            fileObject.id == claim.fileObjectId &&
                fileObject.tenantId == session.tenantId &&
                fileObject.fileName == session.fileName &&
                fileObject.contentLength == session.contentLength &&
                fileObject.storageType == finalization.storedObject.location.storageType &&
                fileObject.storagePath == finalization.storedObject.location.path &&
                fileObject.contentType == session.contentType &&
                fileObject.contentHash == session.contentHash
        val exactAsset =
            fileAsset.id == claim.fileAssetId &&
                fileAsset.tenantId == session.tenantId &&
                fileAsset.fileObjectId == fileObject.id &&
                fileAsset.assetType == PRESIGNED_UPLOAD_ASSET_TYPE &&
                fileAsset.metadata == session.metadata
        if (!exactObject || !exactAsset) {
            throw CompletedPresignedUploadAssetClaimStateException(
                "Claimed FileObject/FileAsset does not exactly match provider completion evidence.",
            )
        }
    }

    private fun requireClaimCapability(): CompletedPresignedUploadAssetClaimRepository =
        uploadSessions as? CompletedPresignedUploadAssetClaimRepository
            ?: throw CompletedPresignedUploadAssetClaimUnavailableException()

    private fun sameClaim(
        first: CompletedPresignedUploadAssetClaim?,
        second: CompletedPresignedUploadAssetClaim,
    ): Boolean = first != null &&
        first.tenantId == second.tenantId &&
        first.uploadId == second.uploadId &&
        first.fileObjectId == second.fileObjectId &&
        first.fileAssetId == second.fileAssetId &&
        first.idempotencyKeyDigest == second.idempotencyKeyDigest &&
        first.purpose == second.purpose &&
        first.claimedBy == second.claimedBy &&
        first.claimedTime == second.claimedTime

    private fun CompletedPresignedUploadAssetClaim.toResult(replayed: Boolean) =
        CompletedPresignedUploadAssetClaimResult(uploadId, fileObjectId, fileAssetId, replayed)

    private fun CompletedPresignedUploadAssetClaimResult.withReplay(replayed: Boolean) =
        CompletedPresignedUploadAssetClaimResult(uploadId, fileObjectId, fileAssetId, replayed)

    private fun nonNegativeNow(): Long = clock.millis().also { now ->
        if (now < 0) throw CompletedPresignedUploadAssetClaimStateException("System clock returned an invalid time.")
    }

    private class ClaimRequestContext(
        val tenantId: Identifier,
        val user: UserIdentity,
        val ownerId: String,
    )

    private companion object {
        const val FILE_OBJECT_RESOURCE_TYPE: String = "FILE_OBJECT"
        const val FILE_ASSET_RESOURCE_TYPE: String = "FILE_ASSET"
    }
}
