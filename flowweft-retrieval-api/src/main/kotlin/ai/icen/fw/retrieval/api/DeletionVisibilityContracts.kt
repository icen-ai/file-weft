package ai.icen.fw.retrieval.api

import ai.icen.fw.core.id.Identifier

/**
 * Exact revision of one host-owned resource whose deletion state must be checked before use.
 *
 * A resource id without a revision is intentionally insufficient: an answer for an old version
 * must never authorize a newer version, and a tombstone for a newer revision must not be confused
 * with an unrelated historical object.
 */
class RetrievalDeletionResourceRef private constructor(
    val tenantId: Identifier,
    resourceType: String,
    val resourceId: Identifier,
    resourceRevision: String,
) {
    val resourceType: String = requireResourceType(resourceType)
    val resourceRevision: String = resourceRevision.also {
        requireRetrievalText(
            it,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Deletion visibility resource revision is invalid.",
        )
    }
    val digest: String

    init {
        requireRetrievalIdentifier(tenantId, "Deletion visibility tenant identifier is invalid.")
        requireRetrievalIdentifier(resourceId, "Deletion visibility resource identifier is invalid.")
        digest = retrievalDigest {
            text("flowweft-retrieval-deletion-resource-ref-v1")
            text(tenantId.value)
            text(this@RetrievalDeletionResourceRef.resourceType)
            text(resourceId.value)
            text(this@RetrievalDeletionResourceRef.resourceRevision)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is RetrievalDeletionResourceRef && digest == other.digest

    override fun hashCode(): Int = digest.hashCode()

    override fun toString(): String = "RetrievalDeletionResourceRef(type=$resourceType)"

    companion object {
        const val DOCUMENT_RESOURCE_TYPE: String = "document"

        @JvmStatic
        fun of(
            tenantId: Identifier,
            resourceType: String,
            resourceId: Identifier,
            resourceRevision: String,
        ): RetrievalDeletionResourceRef = RetrievalDeletionResourceRef(
            tenantId,
            resourceType,
            resourceId,
            resourceRevision,
        )

        /** Uses the immutable version id as the document revision observed by retrieval. */
        @JvmStatic
        fun document(
            tenantId: Identifier,
            documentId: Identifier,
            versionId: Identifier,
        ): RetrievalDeletionResourceRef = RetrievalDeletionResourceRef(
            tenantId,
            DOCUMENT_RESOURCE_TYPE,
            documentId,
            versionId.value,
        )

        /** Binds both immutable version identity and the exact source bytes seen by retrieval. */
        @JvmStatic
        fun documentContent(
            tenantId: Identifier,
            documentId: Identifier,
            versionId: Identifier,
            sourceSha256: String,
        ): RetrievalDeletionResourceRef {
            requireDigest(sourceSha256, "Deletion visibility source digest is invalid.")
            val revision = retrievalDigest {
                text("flowweft-retrieval-document-content-revision-v1")
                text(versionId.value)
                text(sourceSha256)
            }
            return RetrievalDeletionResourceRef(
                tenantId,
                DOCUMENT_RESOURCE_TYPE,
                documentId,
                revision,
            )
        }
    }
}

enum class RetrievalDeletionVisibilityState {
    VISIBLE,
    TOMBSTONED,
}

/** Immutable capability snapshot for the authoritative deletion visibility bridge. */
class RetrievalDeletionVisibilityDescriptor private constructor(
    providerTypeId: String,
    providerInstanceId: String,
    configurationDigest: String,
    capabilityRevision: String,
    supportedResourceTypes: Collection<String>,
    val maximumBatchSize: Int,
    val supportsCancellation: Boolean,
    val authoritative: Boolean,
    val sendsResourceMetadataOffHost: Boolean,
) {
    val providerTypeId: String = providerTypeId.also {
        requireRetrievalText(
            it,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Deletion visibility provider type is invalid.",
        )
    }
    val providerInstanceId: String = providerInstanceId.also {
        requireRetrievalText(
            it,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Deletion visibility provider instance is invalid.",
        )
    }
    val configurationDigest: String = configurationDigest.also {
        requireDigest(it, "Deletion visibility provider configuration digest is invalid.")
    }
    val capabilityRevision: String = capabilityRevision.also {
        requireRetrievalText(
            it,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Deletion visibility capability revision is invalid.",
        )
    }
    val supportedResourceTypes: Set<String> = immutableRetrievalSet(
        supportedResourceTypes.map(::requireResourceType),
    )
    val capabilityDigest: String
    val digest: String
    val binding: RetrievalStageProviderBinding

    init {
        require(this.supportedResourceTypes.isNotEmpty()) {
            "Deletion visibility provider must support at least one resource type."
        }
        require(maximumBatchSize in 1..MAX_DELETION_VISIBILITY_BATCH_SIZE) {
            "Deletion visibility batch limit is invalid."
        }
        require(authoritative) { "Deletion visibility provider must be authoritative." }
        require(!sendsResourceMetadataOffHost) {
            "Deletion visibility resource metadata must remain on host."
        }
        capabilityDigest = retrievalDigest {
            text("flowweft-retrieval-deletion-visibility-capability-v1")
            val types = this@RetrievalDeletionVisibilityDescriptor.supportedResourceTypes.sorted()
            integer(types.size)
            types.forEach(::text)
            integer(maximumBatchSize)
            boolean(supportsCancellation)
            boolean(authoritative)
            boolean(sendsResourceMetadataOffHost)
        }
        digest = retrievalDigest {
            text("flowweft-retrieval-deletion-visibility-descriptor-v1")
            text(this@RetrievalDeletionVisibilityDescriptor.providerTypeId)
            text(this@RetrievalDeletionVisibilityDescriptor.providerInstanceId)
            text(this@RetrievalDeletionVisibilityDescriptor.configurationDigest)
            text(this@RetrievalDeletionVisibilityDescriptor.capabilityRevision)
            text(capabilityDigest)
        }
        binding = RetrievalStageProviderBinding.create(
            "deletion-visibility",
            this.providerTypeId,
            this.providerInstanceId,
            this.configurationDigest,
            capabilityDigest,
            this.capabilityRevision,
            digest,
            supportsCancellation,
        )
    }

    fun requireSupports(resources: Collection<RetrievalDeletionResourceRef>) {
        require(resources.isNotEmpty() && resources.size <= maximumBatchSize) {
            "Deletion visibility request exceeds provider batch capability."
        }
        require(resources.all { resource -> resource.resourceType in supportedResourceTypes }) {
            "Deletion visibility provider does not support a requested resource type."
        }
    }

    override fun toString(): String =
        "RetrievalDeletionVisibilityDescriptor(providerType=$providerTypeId)"

    companion object {
        const val MAX_DELETION_VISIBILITY_BATCH_SIZE: Int = 1_000

        @JvmStatic
        fun create(
            providerTypeId: String,
            providerInstanceId: String,
            configurationDigest: String,
            capabilityRevision: String,
            supportedResourceTypes: Collection<String>,
            maximumBatchSize: Int,
            supportsCancellation: Boolean,
            authoritative: Boolean,
            sendsResourceMetadataOffHost: Boolean,
        ): RetrievalDeletionVisibilityDescriptor = RetrievalDeletionVisibilityDescriptor(
            providerTypeId,
            providerInstanceId,
            configurationDigest,
            capabilityRevision,
            supportedResourceTypes,
            maximumBatchSize,
            supportsCancellation,
            authoritative,
            sendsResourceMetadataOffHost,
        )
    }
}

/** Exact, single-tenant, bounded visibility query. */
class RetrievalDeletionVisibilityRequest private constructor(
    val requestId: Identifier,
    val tenantId: Identifier,
    resources: Collection<RetrievalDeletionResourceRef>,
    val providerBinding: RetrievalStageProviderBinding,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
) {
    val resources: List<RetrievalDeletionResourceRef> = immutableRetrievalList(
        resources,
        RetrievalDeletionVisibilityDescriptor.MAX_DELETION_VISIBILITY_BATCH_SIZE,
        "Deletion visibility request contains too many resources.",
    )
    val digest: String

    init {
        requireRetrievalIdentifier(requestId, "Deletion visibility request identifier is invalid.")
        requireRetrievalIdentifier(tenantId, "Deletion visibility tenant identifier is invalid.")
        require(providerBinding.stageId == "deletion-visibility") {
            "Deletion visibility request has the wrong provider stage binding."
        }
        require(this.resources.isNotEmpty()) { "Deletion visibility request must not be empty." }
        require(this.resources.all { resource -> resource.tenantId == tenantId }) {
            "Deletion visibility request cannot cross tenants."
        }
        require(this.resources.map { resource -> resource.digest }.toSet().size == this.resources.size) {
            "Deletion visibility request contains duplicate resource revisions."
        }
        require(requestedAtEpochMilli >= 0L) { "Deletion visibility request time is invalid." }
        require(deadlineEpochMilli > requestedAtEpochMilli) {
            "Deletion visibility deadline must follow request time."
        }
        digest = retrievalDigest {
            text("flowweft-retrieval-deletion-visibility-request-v1")
            text(requestId.value)
            text(tenantId.value)
            text(providerBinding.digest)
            integer(this@RetrievalDeletionVisibilityRequest.resources.size)
            this@RetrievalDeletionVisibilityRequest.resources.forEach { resource -> text(resource.digest) }
            long(requestedAtEpochMilli)
            long(deadlineEpochMilli)
        }
    }

    override fun toString(): String =
        "RetrievalDeletionVisibilityRequest(resources=${resources.size})"

    companion object {
        @JvmStatic
        fun create(
            requestId: Identifier,
            resources: Collection<RetrievalDeletionResourceRef>,
            descriptor: RetrievalDeletionVisibilityDescriptor,
            requestedAtEpochMilli: Long,
            deadlineEpochMilli: Long,
        ): RetrievalDeletionVisibilityRequest {
            val snapshot = immutableRetrievalList(
                resources,
                RetrievalDeletionVisibilityDescriptor.MAX_DELETION_VISIBILITY_BATCH_SIZE,
                "Deletion visibility request contains too many resources.",
            )
            descriptor.requireSupports(snapshot)
            val tenantId = snapshot.first().tenantId
            return RetrievalDeletionVisibilityRequest(
                requestId,
                tenantId,
                snapshot,
                descriptor.binding,
                requestedAtEpochMilli,
                deadlineEpochMilli,
            )
        }
    }
}

/** One authoritative answer, bound to the exact tenant, type, id and observed revision. */
class RetrievalDeletionVisibilityDecision private constructor(
    val resource: RetrievalDeletionResourceRef,
    val state: RetrievalDeletionVisibilityState,
    authorityRevision: String,
    tombstoneRevision: String?,
    val decidedAtEpochMilli: Long,
) {
    val authorityRevision: String = authorityRevision.also {
        requireRetrievalText(
            it,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Deletion visibility authority revision is invalid.",
        )
    }
    val tombstoneRevision: String? = tombstoneRevision?.also { value ->
        requireRetrievalText(
            value,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Deletion tombstone revision is invalid.",
        )
    }
    val digest: String

    init {
        require((state == RetrievalDeletionVisibilityState.TOMBSTONED) == (this.tombstoneRevision != null)) {
            "Deletion visibility state and tombstone revision are inconsistent."
        }
        require(decidedAtEpochMilli >= 0L) { "Deletion visibility decision time is invalid." }
        digest = retrievalDigest {
            text("flowweft-retrieval-deletion-visibility-decision-v1")
            text(resource.digest)
            text(state.name)
            text(this@RetrievalDeletionVisibilityDecision.authorityRevision)
            optionalText(this@RetrievalDeletionVisibilityDecision.tombstoneRevision)
            long(decidedAtEpochMilli)
        }
    }

    fun isVisible(): Boolean = state == RetrievalDeletionVisibilityState.VISIBLE

    override fun toString(): String = "RetrievalDeletionVisibilityDecision(state=$state)"

    companion object {
        @JvmStatic
        fun visible(
            resource: RetrievalDeletionResourceRef,
            authorityRevision: String,
            decidedAtEpochMilli: Long,
        ): RetrievalDeletionVisibilityDecision = RetrievalDeletionVisibilityDecision(
            resource,
            RetrievalDeletionVisibilityState.VISIBLE,
            authorityRevision,
            null,
            decidedAtEpochMilli,
        )

        @JvmStatic
        fun tombstoned(
            resource: RetrievalDeletionResourceRef,
            authorityRevision: String,
            tombstoneRevision: String,
            decidedAtEpochMilli: Long,
        ): RetrievalDeletionVisibilityDecision = RetrievalDeletionVisibilityDecision(
            resource,
            RetrievalDeletionVisibilityState.TOMBSTONED,
            authorityRevision,
            tombstoneRevision,
            decidedAtEpochMilli,
        )
    }
}

/** Provider response that must account for every requested resource in the original order. */
class RetrievalDeletionVisibilityBatch private constructor(
    val requestId: Identifier,
    requestDigest: String,
    providerBindingDigest: String,
    decisions: Collection<RetrievalDeletionVisibilityDecision>,
    val completedAtEpochMilli: Long,
) {
    val requestDigest: String = requestDigest.also {
        requireDigest(it, "Deletion visibility response request digest is invalid.")
    }
    val providerBindingDigest: String = providerBindingDigest.also {
        requireDigest(it, "Deletion visibility response provider binding is invalid.")
    }
    val decisions: List<RetrievalDeletionVisibilityDecision> = immutableRetrievalList(
        decisions,
        RetrievalDeletionVisibilityDescriptor.MAX_DELETION_VISIBILITY_BATCH_SIZE,
        "Deletion visibility response contains too many decisions.",
    )
    val digest: String

    init {
        requireRetrievalIdentifier(requestId, "Deletion visibility response request identifier is invalid.")
        require(this.decisions.isNotEmpty()) { "Deletion visibility response must not be empty." }
        require(this.decisions.map { decision -> decision.resource.digest }.toSet().size == this.decisions.size) {
            "Deletion visibility response contains duplicate resource revisions."
        }
        require(completedAtEpochMilli >= 0L) { "Deletion visibility response time is invalid." }
        digest = retrievalDigest {
            text("flowweft-retrieval-deletion-visibility-batch-v1")
            text(requestId.value)
            text(this@RetrievalDeletionVisibilityBatch.requestDigest)
            text(this@RetrievalDeletionVisibilityBatch.providerBindingDigest)
            integer(this@RetrievalDeletionVisibilityBatch.decisions.size)
            this@RetrievalDeletionVisibilityBatch.decisions.forEach { decision -> text(decision.digest) }
            long(completedAtEpochMilli)
        }
    }

    fun requireExactFor(
        request: RetrievalDeletionVisibilityRequest,
        descriptor: RetrievalDeletionVisibilityDescriptor,
    ) {
        require(
            requestId == request.requestId &&
                requestDigest == request.digest &&
                providerBindingDigest == request.providerBinding.digest &&
                providerBindingDigest == descriptor.binding.digest &&
                completedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli,
        ) { "Deletion visibility response does not match its exact request and provider." }
        require(decisions.size == request.resources.size) {
            "Deletion visibility response must account for every requested resource."
        }
        request.resources.zip(decisions).forEach { (resource, decision) ->
            require(resource.digest == decision.resource.digest &&
                resource.tenantId == decision.resource.tenantId &&
                resource.resourceType == decision.resource.resourceType &&
                resource.resourceId == decision.resource.resourceId &&
                resource.resourceRevision == decision.resource.resourceRevision
            ) { "Deletion visibility response changed resource order or binding." }
            require(decision.decidedAtEpochMilli in request.requestedAtEpochMilli..completedAtEpochMilli) {
                "Deletion visibility decision is outside its request window."
            }
        }
    }

    override fun toString(): String =
        "RetrievalDeletionVisibilityBatch(decisions=${decisions.size})"

    companion object {
        @JvmStatic
        fun create(
            request: RetrievalDeletionVisibilityRequest,
            descriptor: RetrievalDeletionVisibilityDescriptor,
            decisions: Collection<RetrievalDeletionVisibilityDecision>,
            completedAtEpochMilli: Long,
        ): RetrievalDeletionVisibilityBatch = RetrievalDeletionVisibilityBatch(
            request.requestId,
            request.digest,
            descriptor.binding.digest,
            decisions,
            completedAtEpochMilli,
        ).also { response -> response.requireExactFor(request, descriptor) }
    }
}

/** Trusted host bridge; an absent implementation is a fail-closed unsupported capability. */
interface RetrievalDeletionVisibilityProvider {
    fun descriptor(): RetrievalDeletionVisibilityDescriptor
    fun inspect(request: RetrievalDeletionVisibilityRequest): RetrievalCall<RetrievalDeletionVisibilityBatch>
}

private fun requireResourceType(value: String): String = value.also {
    requireRetrievalText(it, 64, "Deletion visibility resource type is invalid.")
    require(Regex("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$").matches(it)) {
        "Deletion visibility resource type is invalid."
    }
}
