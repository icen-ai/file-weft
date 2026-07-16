package ai.icen.fw.retrieval.runtime

import ai.icen.fw.retrieval.api.RetrievalDeletionResourceRef
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityProvider
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityState
import ai.icen.fw.retrieval.api.RetrievalFailureCode
import ai.icen.fw.retrieval.api.RetrievalProviderException
import ai.icen.fw.retrieval.api.RetrievalRetryability
import ai.icen.fw.retrieval.spi.ContentSourceRef
import ai.icen.fw.retrieval.spi.RetrievalIndexActivationReceipt
import ai.icen.fw.retrieval.spi.RetrievalIndexActivationRequest
import ai.icen.fw.retrieval.spi.RetrievalIndexMutationKind
import ai.icen.fw.retrieval.spi.RetrievalIndexMutationReceipt
import ai.icen.fw.retrieval.spi.RetrievalIndexMutationRequest
import ai.icen.fw.retrieval.spi.RetrievalIndexProvider
import ai.icen.fw.retrieval.spi.RetrievalIndexProviderDescriptor
import ai.icen.fw.retrieval.spi.RetrievalIndexSealReceipt
import ai.icen.fw.retrieval.spi.RetrievalIndexSealRequest
import ai.icen.fw.retrieval.spi.RetrievalIndexStageBatch
import ai.icen.fw.retrieval.spi.RetrievalIndexStageReceipt
import ai.icen.fw.retrieval.spi.RetrievalIndexState
import ai.icen.fw.retrieval.spi.RetrievalIndexStateRequest
import java.util.concurrent.CompletionStage
import java.util.function.LongSupplier

/**
 * Provider-neutral deletion fence for every index boundary that can publish or refresh content.
 *
 * Stage, seal, activation and authorization refresh require a visible exact source revision both
 * before and after the delegated operation. Tombstone propagation requires the authoritative
 * tombstone before mutation and verifies it again after the exact mutation receipt is returned.
 */
class DeletionFencedRetrievalIndexProvider private constructor(
    private val delegate: RetrievalIndexProvider,
    private val descriptorSnapshot: RetrievalIndexProviderDescriptor,
    private val deletionVisibility: DeletionVisibilityRuntimeGuard,
) : RetrievalIndexProvider {
    override fun descriptor(): RetrievalIndexProviderDescriptor {
        requireSameIndexDescriptor()
        deletionVisibility.requireSameDescriptor()
        return descriptorSnapshot
    }

    override fun stage(request: RetrievalIndexStageBatch): CompletionStage<RetrievalIndexStageReceipt> {
        requireRequestDescriptor(request.manifest.descriptor)
        val source = request.manifest.source
        return requireVisibility(source, request.deadlineEpochMilli, RetrievalDeletionVisibilityState.VISIBLE)
            .thenCompose { delegate.stage(request) }
            .thenCompose { receipt ->
                requireExactStageReceipt(request, receipt)
                requireVisibility(source, request.deadlineEpochMilli, RetrievalDeletionVisibilityState.VISIBLE)
                    .thenApply { receipt }
            }
    }

    override fun seal(request: RetrievalIndexSealRequest): CompletionStage<RetrievalIndexSealReceipt> {
        requireRequestDescriptor(request.manifest.descriptor)
        val source = request.manifest.source
        return requireVisibility(source, request.deadlineEpochMilli, RetrievalDeletionVisibilityState.VISIBLE)
            .thenCompose { delegate.seal(request) }
            .thenCompose { receipt ->
                if (receipt.request.digest != request.digest || receipt.visibleToQueries) {
                    throw providerBindingMismatch()
                }
                requireVisibility(source, request.deadlineEpochMilli, RetrievalDeletionVisibilityState.VISIBLE)
                    .thenApply { receipt }
            }
    }

    override fun activate(request: RetrievalIndexActivationRequest): CompletionStage<RetrievalIndexActivationReceipt> {
        val manifest = request.sealReceipt.request.manifest
        requireRequestDescriptor(manifest.descriptor)
        val source = manifest.source
        return requireVisibility(source, request.deadlineEpochMilli, RetrievalDeletionVisibilityState.VISIBLE)
            .thenCompose { delegate.activate(request) }
            .thenCompose { receipt ->
                if (!(receipt.requestId == request.requestId &&
                        receipt.requestDigest == request.digest &&
                        receipt.activeGenerationId == manifest.generationId &&
                        receipt.previousGenerationId == request.expectedPreviousGenerationId &&
                        receipt.previousProjectionRevision == request.expectedProjectionRevision &&
                        receipt.activeProjectionRevision == request.expectedProjectionRevision + 1L &&
                        receipt.atomicSwitch &&
                        receipt.activatedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli)
                ) {
                    throw providerBindingMismatch()
                }
                requireVisibility(source, request.deadlineEpochMilli, RetrievalDeletionVisibilityState.VISIBLE)
                    .thenApply { receipt }
            }
    }

    override fun mutate(request: RetrievalIndexMutationRequest): CompletionStage<RetrievalIndexMutationReceipt> {
        requireRequestDescriptor(request.descriptor)
        val expectedState = when (request.kind) {
            RetrievalIndexMutationKind.TOMBSTONE -> RetrievalDeletionVisibilityState.TOMBSTONED
            RetrievalIndexMutationKind.AUTHORIZATION_REFRESH -> RetrievalDeletionVisibilityState.VISIBLE
            else -> throw providerBindingMismatch()
        }
        return requireVisibility(request.source, request.deadlineEpochMilli, expectedState)
            .thenCompose { delegate.mutate(request) }
            .thenCompose { receipt ->
                if (!(receipt.requestId == request.requestId &&
                        receipt.requestDigest == request.digest &&
                        receipt.kind == request.kind &&
                        receipt.generationId == request.activeGenerationId &&
                        receipt.previousProjectionRevision == request.expectedProjectionRevision &&
                        receipt.activeProjectionRevision == request.expectedProjectionRevision + 1L &&
                        receipt.affectedRecordCount > 0 &&
                        receipt.completedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli)
                ) {
                    throw providerBindingMismatch()
                }
                requireVisibility(request.source, request.deadlineEpochMilli, expectedState).thenApply { receipt }
            }
    }

    /** State remains observable after deletion so repair workers can diagnose incomplete convergence. */
    override fun state(request: RetrievalIndexStateRequest): CompletionStage<RetrievalIndexState> {
        requireRequestDescriptor(request.descriptor)
        return delegate.state(request).thenApply { state ->
            if (!(state.requestId == request.requestId &&
                    state.requestDigest == request.digest &&
                    state.descriptorDigest == request.descriptor.digest &&
                    state.sourceDigest == request.source.digest &&
                    state.observedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli)
            ) {
                throw providerBindingMismatch()
            }
            state
        }
    }

    private fun requireVisibility(
        source: ContentSourceRef,
        deadlineEpochMilli: Long,
        expectedState: RetrievalDeletionVisibilityState,
    ): CompletionStage<Unit> {
        requireSameIndexDescriptor()
        deletionVisibility.requireSameDescriptor()
        val resource = source.deletionResource()
        val call = deletionVisibility.inspect(listOf(resource), deadlineEpochMilli)
        val completion = requireNotNull(call.completion()) {
            "Deletion visibility provider returned no completion stage."
        }
        return completion.thenApply { inspection ->
            deletionVisibility.requireSameDescriptor()
            val visible = inspection.isVisible(resource)
            val matches = when (expectedState) {
                RetrievalDeletionVisibilityState.VISIBLE -> visible
                RetrievalDeletionVisibilityState.TOMBSTONED -> !visible
            }
            if (!matches) {
                throw RetrievalProviderException(
                    if (expectedState == RetrievalDeletionVisibilityState.VISIBLE) {
                        RetrievalFailureCode.RESOURCE_TOMBSTONED
                    } else {
                        RetrievalFailureCode.DELETION_VISIBILITY_MISMATCH
                    },
                    RetrievalRetryability.NOT_RETRYABLE,
                )
            }
            Unit
        }
    }

    private fun requireRequestDescriptor(actual: RetrievalIndexProviderDescriptor) {
        requireSameIndexDescriptor()
        if (actual.digest != descriptorSnapshot.digest ||
            actual.providerInstanceId != descriptorSnapshot.providerInstanceId ||
            actual.providerRevision != descriptorSnapshot.providerRevision ||
            actual.indexSchemaRevision != descriptorSnapshot.indexSchemaRevision
        ) {
            throw providerBindingMismatch()
        }
    }

    private fun requireSameIndexDescriptor() {
        val actual = try {
            requireNotNull(delegate.descriptor()) { "Index provider returned no descriptor." }
        } catch (failure: RetrievalProviderException) {
            throw failure
        } catch (_: RuntimeException) {
            throw providerBindingMismatch()
        }
        if (actual.digest != descriptorSnapshot.digest ||
            actual.providerId != descriptorSnapshot.providerId ||
            actual.providerInstanceId != descriptorSnapshot.providerInstanceId ||
            actual.providerRevision != descriptorSnapshot.providerRevision ||
            actual.indexSchemaRevision != descriptorSnapshot.indexSchemaRevision
        ) {
            throw providerBindingMismatch()
        }
    }

    companion object {
        @JvmStatic
        fun create(
            delegate: RetrievalIndexProvider,
            deletionVisibilityProvider: RetrievalDeletionVisibilityProvider,
            clock: LongSupplier,
            ids: RetrievalRuntimeIdGenerator,
        ): DeletionFencedRetrievalIndexProvider {
            val descriptor = try {
                requireNotNull(delegate.descriptor()) { "Index provider returned no descriptor." }
            } catch (failure: RetrievalProviderException) {
                throw failure
            } catch (_: RuntimeException) {
                throw providerBindingMismatch()
            }
            return DeletionFencedRetrievalIndexProvider(
                delegate,
                descriptor,
                DeletionVisibilityRuntimeGuard.create(deletionVisibilityProvider, clock, ids),
            )
        }

        private fun requireExactStageReceipt(
            request: RetrievalIndexStageBatch,
            receipt: RetrievalIndexStageReceipt,
        ) {
            if (!(receipt.requestId == request.requestId &&
                    receipt.requestDigest == request.digest &&
                    receipt.manifestDigest == request.manifest.digest &&
                    receipt.descriptorDigest == request.manifest.descriptor.digest &&
                    receipt.batchOrdinal == request.batchOrdinal &&
                    receipt.fromRecordInclusive == request.fromRecordInclusive &&
                    receipt.toRecordExclusive == request.toRecordExclusive &&
                    receipt.finalBatch == request.finalBatch &&
                    receipt.recordManifestDigest == request.recordManifestDigest &&
                    !receipt.visibleToQueries &&
                    receipt.completedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli)
            ) {
                throw providerBindingMismatch()
            }
        }

        private fun providerBindingMismatch(): RetrievalProviderException = RetrievalProviderException(
            RetrievalFailureCode.INDEX_PROVIDER_BINDING_MISMATCH,
            RetrievalRetryability.NOT_RETRYABLE,
        )
    }
}

private fun ContentSourceRef.deletionResource(): RetrievalDeletionResourceRef =
    RetrievalDeletionResourceRef.documentContent(tenantId, documentId, versionId, sourceSha256)
