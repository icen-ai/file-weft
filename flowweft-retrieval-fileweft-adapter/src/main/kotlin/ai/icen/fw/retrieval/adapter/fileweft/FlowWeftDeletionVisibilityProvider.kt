package ai.icen.fw.retrieval.adapter.fileweft

import ai.icen.fw.application.retention.DeletionVisibilityFence
import ai.icen.fw.application.retention.DeletionVisibilityQuery
import ai.icen.fw.application.retention.DeletionVisibilityUnavailableException
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.RetrievalCall
import ai.icen.fw.retrieval.api.RetrievalCalls
import ai.icen.fw.retrieval.api.RetrievalDeletionResourceRef
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityBatch
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityDecision
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityDescriptor
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityProvider
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityRequest
import ai.icen.fw.retrieval.api.RetrievalFailureCode
import ai.icen.fw.retrieval.api.RetrievalProviderException
import ai.icen.fw.retrieval.api.RetrievalRetryability
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashSet
import java.util.function.LongSupplier

/**
 * Host bridge from FileWeft's durable secure-deletion tombstone projection to the
 * provider-neutral FlowWeft retrieval fence.
 *
 * This provider is deliberately synchronous and local: resource identifiers and
 * deletion evidence never leave the host. Any missing schema, malformed projection,
 * descriptor drift, deadline expiry, or partial batch is converted to a sanitized,
 * fail-closed retrieval failure.
 */
class FlowWeftDeletionVisibilityProvider(
    private val query: DeletionVisibilityQuery,
    providerInstanceId: String,
    configurationDigest: String,
    capabilityRevision: String,
    private val clock: LongSupplier,
) : RetrievalDeletionVisibilityProvider {
    private val providerDescriptor = RetrievalDeletionVisibilityDescriptor.create(
        PROVIDER_TYPE_ID,
        providerInstanceId,
        configurationDigest,
        capabilityRevision,
        listOf(RetrievalDeletionResourceRef.DOCUMENT_RESOURCE_TYPE),
        DeletionVisibilityQuery.MAX_BATCH_SIZE,
        false,
        true,
        false,
    )

    @JvmOverloads
    constructor(
        query: DeletionVisibilityQuery,
        configurationDigest: String,
        clock: LongSupplier = LongSupplier { System.currentTimeMillis() },
    ) : this(
        query,
        DEFAULT_PROVIDER_INSTANCE_ID,
        configurationDigest,
        CAPABILITY_REVISION,
        clock,
    )

    override fun descriptor(): RetrievalDeletionVisibilityDescriptor = providerDescriptor

    override fun inspect(
        request: RetrievalDeletionVisibilityRequest,
    ): RetrievalCall<RetrievalDeletionVisibilityBatch> {
        requireExactProviderBinding(request)
        val now = clock.asLong
        if (now < request.requestedAtEpochMilli || now >= request.deadlineEpochMilli) {
            throw RetrievalProviderException(
                RetrievalFailureCode.CANCELLED,
                RetrievalRetryability.NOT_RETRYABLE,
                request.requestId.value,
            )
        }

        val resourceIds = LinkedHashSet<Identifier>()
        request.resources.forEach { resource ->
            if (resource.tenantId != request.tenantId ||
                resource.resourceType != RetrievalDeletionResourceRef.DOCUMENT_RESOURCE_TYPE
            ) {
                throw mismatch(request)
            }
            resourceIds += resource.resourceId
        }

        val fences = try {
            requireNotNull(
                query.findFences(
                    request.tenantId,
                    RetrievalDeletionResourceRef.DOCUMENT_RESOURCE_TYPE,
                    resourceIds,
                ),
            ) { "Deletion visibility query returned no batch." }
        } catch (failure: DeletionVisibilityUnavailableException) {
            throw unavailable(request)
        } catch (failure: Exception) {
            throw unavailable(request)
        }

        if (fences.keys.any { resourceId -> resourceId !in resourceIds }) {
            throw mismatch(request)
        }
        fences.forEach { (resourceId, fence) ->
            if (!fence.matches(
                    request.tenantId,
                    RetrievalDeletionResourceRef.DOCUMENT_RESOURCE_TYPE,
                    resourceId,
                )
            ) {
                throw mismatch(request)
            }
        }

        val decisions = request.resources.map { resource ->
            val fence = fences[resource.resourceId]
            if (fence == null) {
                RetrievalDeletionVisibilityDecision.visible(
                    resource,
                    visibleAuthorityRevision(request, resource),
                    now,
                )
            } else {
                RetrievalDeletionVisibilityDecision.tombstoned(
                    resource,
                    fenceAuthorityRevision(fence),
                    fenceTombstoneRevision(fence),
                    now,
                )
            }
        }

        val response = try {
            RetrievalDeletionVisibilityBatch.create(request, providerDescriptor, decisions, now)
        } catch (failure: Exception) {
            throw mismatch(request)
        }
        return RetrievalCalls.completed(response)
    }

    private fun requireExactProviderBinding(request: RetrievalDeletionVisibilityRequest) {
        try {
            providerDescriptor.requireSupports(request.resources)
        } catch (failure: Exception) {
            throw mismatch(request)
        }
        if (request.providerBinding.digest != providerDescriptor.binding.digest) {
            throw mismatch(request)
        }
    }

    private fun visibleAuthorityRevision(
        request: RetrievalDeletionVisibilityRequest,
        resource: RetrievalDeletionResourceRef,
    ): String = digest(
        "flowweft-fileweft-deletion-visible-v1",
        providerDescriptor.digest,
        request.tenantId.value,
        resource.resourceType,
        resource.resourceId.value,
    )

    private fun fenceAuthorityRevision(fence: DeletionVisibilityFence): String = digest(
        "flowweft-fileweft-deletion-authority-v1",
        providerDescriptor.digest,
        fence.tenantId.value,
        fence.resourceType,
        fence.resourceId.value,
        fence.resourceRevision.toString(),
        fence.blockedAt.toString(),
    )

    private fun fenceTombstoneRevision(fence: DeletionVisibilityFence): String = digest(
        "flowweft-fileweft-deletion-tombstone-v1",
        fence.tombstoneId.value,
        fence.planId.value,
        fence.resourceRevision.toString(),
        fence.blockedAt.toString(),
    )

    private fun unavailable(request: RetrievalDeletionVisibilityRequest): RetrievalProviderException =
        RetrievalProviderException(
            RetrievalFailureCode.DELETION_VISIBILITY_UNAVAILABLE,
            RetrievalRetryability.RETRYABLE,
            request.requestId.value,
        )

    private fun mismatch(request: RetrievalDeletionVisibilityRequest): RetrievalProviderException =
        RetrievalProviderException(
            RetrievalFailureCode.DELETION_VISIBILITY_MISMATCH,
            RetrievalRetryability.NOT_RETRYABLE,
            request.requestId.value,
        )

    private fun digest(domain: String, vararg values: String): String {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        sequenceOf(domain, *values).forEach { value ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            messageDigest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            messageDigest.update(bytes)
        }
        val result = messageDigest.digest()
        val encoded = StringBuilder(result.size * 2)
        result.forEach { byte -> encoded.append(String.format("%02x", byte.toInt() and 0xff)) }
        return encoded.toString()
    }

    companion object {
        const val PROVIDER_TYPE_ID: String = "flowweft.fileweft-deletion-visibility"
        const val DEFAULT_PROVIDER_INSTANCE_ID: String = "flowweft-host"
        const val CAPABILITY_REVISION: String = "1"
    }
}
