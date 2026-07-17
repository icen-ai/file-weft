package ai.icen.fw.application.retention

import ai.icen.fw.core.id.Identifier
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * Tenant-bound read projection for the durable secure-deletion tombstone.
 *
 * The projection deliberately omits policy, legal-hold and authorization
 * evidence. Normal read paths only need the immutable denial fence; detailed
 * evidence remains available through the audited secure-deletion status path.
 */
class DeletionVisibilityFence(
    val tombstoneId: Identifier,
    val planId: Identifier,
    val tenantId: Identifier,
    resourceType: String,
    val resourceId: Identifier,
    val resourceRevision: Long,
    val blockedAt: Long,
) {
    val resourceType: String = requireVisibilityText(resourceType, "Deletion resource type", MAX_RESOURCE_TYPE_LENGTH)

    init {
        require(resourceRevision >= 0L) { "Deletion resource revision must not be negative." }
        require(blockedAt >= 0L) { "Deletion visibility fence time must not be negative." }
    }

    fun matches(tenantId: Identifier, resourceType: String, resourceId: Identifier): Boolean =
        this.tenantId == tenantId && this.resourceType == resourceType && this.resourceId == resourceId

    private companion object {
        const val MAX_RESOURCE_TYPE_LENGTH = 128
    }
}

/**
 * Query-side persistence port for the deletion visibility fence.
 *
 * Implementations must execute against the caller-bound transaction and must
 * scope every lookup by tenant, resource type and resource id. A missing table,
 * migration or provider is an error; it must never be interpreted as "visible".
 */
interface DeletionVisibilityQuery {
    fun findFence(
        tenantId: Identifier,
        resourceType: String,
        resourceId: Identifier,
    ): DeletionVisibilityFence?

    /**
     * Bounded batch hook used by page/search projections. The conservative
     * default preserves the same tenant/type binding one lookup at a time.
     */
    fun findFences(
        tenantId: Identifier,
        resourceType: String,
        resourceIds: Collection<Identifier>,
    ): Map<Identifier, DeletionVisibilityFence> {
        if (resourceIds.size > MAX_BATCH_SIZE) {
            throw IllegalArgumentException("Deletion visibility batch is too large.")
        }
        val uniqueIds = LinkedHashSet(resourceIds)
        require(uniqueIds.size == resourceIds.size) {
            "Deletion visibility batch resource identifiers must be unique."
        }
        val result = LinkedHashMap<Identifier, DeletionVisibilityFence>()
        uniqueIds.forEach { resourceId ->
            findFence(tenantId, resourceType, resourceId)?.let { fence -> result[resourceId] = fence }
        }
        return Collections.unmodifiableMap(result)
    }

    companion object {
        const val MAX_BATCH_SIZE: Int = 256
    }
}

/** Marker for repository ports that expose the mandatory tombstone query. */
interface DeletionVisibilityQuerySource {
    fun deletionVisibilityQuery(): DeletionVisibilityQuery
}

/**
 * Fail-closed policy shared by document reads, downloads and asynchronous
 * projections. A resource-level read is denied by any tombstone. Revisioned
 * writes/hydration are denied when their revision is not newer than the fence.
 */
class DeletionVisibilityGuard private constructor(
    private val query: DeletionVisibilityQuery,
) {
    /**
     * Proves that the durable projection is queryable before a SQL-backed
     * collection read whose resource identifiers are not known yet.
     */
    fun requireAvailable(
        tenantId: Identifier,
        resourceType: String,
    ) {
        findValidatedFence(tenantId, resourceType, CAPABILITY_PROBE_RESOURCE_ID)
    }

    fun requireResourceVisible(
        tenantId: Identifier,
        resourceType: String,
        resourceId: Identifier,
    ) {
        findValidatedFence(tenantId, resourceType, resourceId)?.let {
            throw DeletedResourceNotVisibleException()
        }
    }

    /**
     * Rejects a stale cache, retrieval hydration or index upsert. A strictly
     * newer revision can proceed only through a caller that deliberately uses
     * this revision-aware method; ordinary reads remain permanently fenced.
     */
    fun requireRevisionVisible(
        tenantId: Identifier,
        resourceType: String,
        resourceId: Identifier,
        resourceRevision: Long,
    ) {
        require(resourceRevision >= 0L) { "Deletion visibility resource revision must not be negative." }
        val fence = findValidatedFence(tenantId, resourceType, resourceId) ?: return
        if (resourceRevision <= fence.resourceRevision) {
            throw DeletedResourceNotVisibleException()
        }
    }

    /** Removes tombstoned rows from a bounded normal-read page. */
    fun visibleResourceIds(
        tenantId: Identifier,
        resourceType: String,
        resourceIds: Collection<Identifier>,
    ): Set<Identifier> {
        val safeResourceType = requireVisibilityText(resourceType, "Deletion resource type", MAX_RESOURCE_TYPE_LENGTH)
        if (resourceIds.size > DeletionVisibilityQuery.MAX_BATCH_SIZE) {
            throw DeletionVisibilityUnavailableException()
        }
        val uniqueIds = LinkedHashSet(resourceIds)
        if (uniqueIds.size != resourceIds.size) {
            throw DeletionVisibilityUnavailableException()
        }
        val fences = try {
            requireNotNull(query.findFences(tenantId, safeResourceType, uniqueIds)) {
                "Deletion visibility provider returned no batch result."
            }
        } catch (failure: DeletionVisibilityUnavailableException) {
            throw failure
        } catch (failure: DeletedResourceNotVisibleException) {
            throw failure
        } catch (failure: Exception) {
            throw DeletionVisibilityUnavailableException(cause = failure)
        }
        if (fences.keys.any { it !in uniqueIds }) {
            throw DeletionVisibilityUnavailableException()
        }
        fences.forEach { (resourceId, fence) ->
            if (!fence.matches(tenantId, safeResourceType, resourceId)) {
                throw DeletionVisibilityUnavailableException()
            }
        }
        return Collections.unmodifiableSet(LinkedHashSet(uniqueIds.filterNot(fences::containsKey)))
    }

    fun fence(
        tenantId: Identifier,
        resourceType: String,
        resourceId: Identifier,
    ): DeletionVisibilityFence? = findValidatedFence(tenantId, resourceType, resourceId)

    private fun findValidatedFence(
        tenantId: Identifier,
        resourceType: String,
        resourceId: Identifier,
    ): DeletionVisibilityFence? {
        val safeResourceType = requireVisibilityText(resourceType, "Deletion resource type", MAX_RESOURCE_TYPE_LENGTH)
        val fence = try {
            query.findFence(tenantId, safeResourceType, resourceId)
        } catch (failure: DeletionVisibilityUnavailableException) {
            throw failure
        } catch (failure: Exception) {
            throw DeletionVisibilityUnavailableException(cause = failure)
        } ?: return null
        if (!fence.matches(tenantId, safeResourceType, resourceId)) {
            throw DeletionVisibilityUnavailableException()
        }
        return fence
    }

    companion object {
        private const val MAX_RESOURCE_TYPE_LENGTH = 128
        private val CAPABILITY_PROBE_RESOURCE_ID = Identifier("flowweft-deletion-visibility-capability-probe")

        @JvmStatic
        fun create(query: DeletionVisibilityQuery): DeletionVisibilityGuard =
            DeletionVisibilityGuard(query)

        /**
         * Resolves the mandatory capability from a repository. Hosts that do
         * not expose it get an explicit unsupported failure, never an allow.
         */
        @JvmStatic
        fun requireFrom(source: Any?): DeletionVisibilityGuard {
            val querySource = source as? DeletionVisibilityQuerySource
                ?: return unavailable()
            return try {
                create(requireNotNull(querySource.deletionVisibilityQuery()))
            } catch (failure: Exception) {
                unavailable(failure)
            }
        }

        @JvmStatic
        fun unavailable(): DeletionVisibilityGuard =
            DeletionVisibilityGuard(UnavailableDeletionVisibilityQuery(null))

        private fun unavailable(cause: Throwable): DeletionVisibilityGuard =
            DeletionVisibilityGuard(UnavailableDeletionVisibilityQuery(cause))
    }
}

/** Normal callers receive the same opaque not-found classification. */
class DeletedResourceNotVisibleException : NoSuchElementException(DEFAULT_MESSAGE) {
    companion object {
        const val DEFAULT_MESSAGE: String = "Resource is not visible."
    }
}

/** Missing/stale schema or a malformed provider response is fail-closed. */
class DeletionVisibilityUnavailableException @JvmOverloads constructor(
    message: String = DEFAULT_MESSAGE,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    companion object {
        const val DEFAULT_MESSAGE: String = "Deletion visibility enforcement is unavailable."
    }
}

private class UnavailableDeletionVisibilityQuery(
    private val failure: Throwable?,
) : DeletionVisibilityQuery {
    override fun findFence(
        tenantId: Identifier,
        resourceType: String,
        resourceId: Identifier,
    ): DeletionVisibilityFence? = throw DeletionVisibilityUnavailableException(cause = failure)

    override fun findFences(
        tenantId: Identifier,
        resourceType: String,
        resourceIds: Collection<Identifier>,
    ): Map<Identifier, DeletionVisibilityFence> =
        throw DeletionVisibilityUnavailableException(cause = failure)
}

private fun requireVisibilityText(value: String, field: String, maximumLength: Int): String {
    require(value.isNotBlank()) { "$field must not be blank." }
    require(value == value.trim()) { "$field must be canonical." }
    require(value.length <= maximumLength) { "$field must not exceed $maximumLength characters." }
    require(value.none { character -> Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt() }) {
        "$field must not contain unsafe characters."
    }
    return value
}
