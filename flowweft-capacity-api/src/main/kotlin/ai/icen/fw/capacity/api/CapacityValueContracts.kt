package ai.icen.fw.capacity.api

import ai.icen.fw.core.id.Identifier

/** Open workload vocabulary; extensions remain machine codes instead of enum failures. */
class WorkloadKind(value: String) : Comparable<WorkloadKind> {
    val value: String = requireCapacityCode(value, "Capacity workload kind")

    override fun compareTo(other: WorkloadKind): Int = value.compareTo(other.value)
    override fun equals(other: Any?): Boolean = other is WorkloadKind && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        const val CONTRACT_VERSION: String = "flowweft.capacity.workload-kind.v1"
        @JvmField val UPLOAD = WorkloadKind("upload")
        @JvmField val SYNC = WorkloadKind("sync")
        @JvmField val INDEX = WorkloadKind("index")
        @JvmField val AGENT = WorkloadKind("agent")
        @JvmField val WORKFLOW = WorkloadKind("workflow")
        @JvmField val STORAGE = WorkloadKind("storage")
        @JvmField val RETRIEVAL = WorkloadKind("retrieval")
        @JvmField val CONNECTOR = WorkloadKind("connector")
    }
}

enum class CapacityScopeLevel(val hierarchyRank: Int) {
    SYSTEM(0),
    TENANT(1),
    PROVIDER(2),
    RESOURCE(3),
}

/** One exact path in the system -> tenant -> provider -> resource hierarchy. */
class ResourceScope private constructor(
    val level: CapacityScopeLevel,
    tenantId: Identifier?,
    providerId: Identifier?,
    resourceType: String?,
    resourceId: Identifier?,
) {
    val tenantId: Identifier? = tenantId?.let { value ->
        requireCapacityIdentifier(value, "Capacity scope tenant identifier")
    }
    val providerId: Identifier? = providerId?.let { value ->
        requireCapacityIdentifier(value, "Capacity scope provider identifier")
    }
    val resourceType: String? = resourceType?.let { value ->
        requireCapacityCode(value, "Capacity scope resource type")
    }
    val resourceId: Identifier? = resourceId?.let { value ->
        requireCapacityIdentifier(value, "Capacity scope resource identifier")
    }
    val bindingDigest: String

    init {
        when (level) {
            CapacityScopeLevel.SYSTEM -> require(
                this.tenantId == null && this.providerId == null &&
                    this.resourceType == null && this.resourceId == null,
            ) { "System capacity scope cannot contain narrower identifiers." }

            CapacityScopeLevel.TENANT -> require(
                this.tenantId != null && this.providerId == null &&
                    this.resourceType == null && this.resourceId == null,
            ) { "Tenant capacity scope has invalid identifiers." }

            CapacityScopeLevel.PROVIDER -> require(
                this.tenantId != null && this.providerId != null &&
                    this.resourceType == null && this.resourceId == null,
            ) { "Provider capacity scope has invalid identifiers." }

            CapacityScopeLevel.RESOURCE -> require(
                this.tenantId != null && this.resourceType != null && this.resourceId != null,
            ) { "Resource capacity scope requires tenant, type and identifier." }
        }
        bindingDigest = CapacityDigest(CONTRACT_VERSION)
            .add(level.name)
            .add(this.tenantId?.value ?: "-")
            .add(this.providerId?.value ?: "-")
            .add(this.resourceType ?: "-")
            .add(this.resourceId?.value ?: "-")
            .finish()
    }

    /** True only when this policy scope is an ancestor of, or equal to, [target]. */
    fun appliesTo(target: ResourceScope): Boolean = when (level) {
        CapacityScopeLevel.SYSTEM -> true
        CapacityScopeLevel.TENANT -> target.level.hierarchyRank >= level.hierarchyRank &&
            tenantId == target.tenantId
        CapacityScopeLevel.PROVIDER -> target.level.hierarchyRank >= level.hierarchyRank &&
            tenantId == target.tenantId && providerId == target.providerId
        CapacityScopeLevel.RESOURCE -> bindingDigest == target.bindingDigest
    }

    override fun equals(other: Any?): Boolean = other is ResourceScope && bindingDigest == other.bindingDigest
    override fun hashCode(): Int = bindingDigest.hashCode()
    override fun toString(): String = "ResourceScope(level=$level, <redacted>)"

    companion object {
        const val CONTRACT_VERSION: String = "flowweft.capacity.resource-scope.v1"

        @JvmStatic
        fun system(): ResourceScope = ResourceScope(CapacityScopeLevel.SYSTEM, null, null, null, null)

        @JvmStatic
        fun tenant(tenantId: Identifier): ResourceScope =
            ResourceScope(CapacityScopeLevel.TENANT, tenantId, null, null, null)

        @JvmStatic
        fun provider(tenantId: Identifier, providerId: Identifier): ResourceScope =
            ResourceScope(CapacityScopeLevel.PROVIDER, tenantId, providerId, null, null)

        @JvmStatic
        @JvmOverloads
        fun resource(
            tenantId: Identifier,
            resourceType: String,
            resourceId: Identifier,
            providerId: Identifier? = null,
        ): ResourceScope = ResourceScope(
            CapacityScopeLevel.RESOURCE,
            tenantId,
            providerId,
            resourceType,
            resourceId,
        )
    }
}

/** Standard unit vocabulary; providers may add stable codes without changing the ABI. */
class CapacityUnit(value: String) {
    val value: String = requireCapacityCode(value, "Capacity unit")

    override fun equals(other: Any?): Boolean = other is CapacityUnit && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        @JvmField val BYTES = CapacityUnit("bytes")
        @JvmField val ITEMS = CapacityUnit("items")
        @JvmField val OPERATIONS = CapacityUnit("operations")
        @JvmField val OPERATIONS_PER_SECOND = CapacityUnit("operations_per_second")
        @JvmField val BYTES_PER_SECOND = CapacityUnit("bytes_per_second")
    }
}

/** Metric identity and canonical unit are inseparable, preventing unit-confused policy merges. */
class CapacityDimension(code: String, val unit: CapacityUnit) : Comparable<CapacityDimension> {
    val code: String = requireCapacityCode(code, "Capacity dimension")
    val bindingCode: String = "$code:${unit.value}"

    override fun compareTo(other: CapacityDimension): Int = bindingCode.compareTo(other.bindingCode)
    override fun equals(other: Any?): Boolean = other is CapacityDimension && bindingCode == other.bindingCode
    override fun hashCode(): Int = bindingCode.hashCode()
    override fun toString(): String = bindingCode

    companion object {
        @JvmField val DISK_BYTES = CapacityDimension("disk_bytes", CapacityUnit.BYTES)
        @JvmField val STORED_BYTES = CapacityDimension("stored_bytes", CapacityUnit.BYTES)
        @JvmField val IN_FLIGHT_BYTES = CapacityDimension("in_flight_bytes", CapacityUnit.BYTES)
        @JvmField val QUEUE_DEPTH = CapacityDimension("queue_depth", CapacityUnit.ITEMS)
        @JvmField val CONCURRENCY = CapacityDimension("concurrency", CapacityUnit.OPERATIONS)
        @JvmField val OPERATION_RATE = CapacityDimension("operation_rate", CapacityUnit.OPERATIONS_PER_SECOND)
        @JvmField val BYTE_RATE = CapacityDimension("byte_rate", CapacityUnit.BYTES_PER_SECOND)
    }
}

class CapacityDemand(val dimension: CapacityDimension, val amount: Long) {
    init {
        require(amount > 0L) { "Capacity demand must be positive." }
    }
}

/** Explicit, capacity-only degradation. Authorization and security controls are never capabilities here. */
class CapacityDegradationCapability(value: String) : Comparable<CapacityDegradationCapability> {
    val value: String = requireCapacityCode(value, "Capacity degradation capability")

    override fun compareTo(other: CapacityDegradationCapability): Int = value.compareTo(other.value)
    override fun equals(other: Any?): Boolean = other is CapacityDegradationCapability && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        @JvmField val DEFER_SECONDARY_INDEXING = CapacityDegradationCapability("defer_secondary_indexing")
        @JvmField val REDUCE_OPTIONAL_ENRICHMENT = CapacityDegradationCapability("reduce_optional_enrichment")
        @JvmField val ASYNC_CONNECTOR_DELIVERY = CapacityDegradationCapability("async_connector_delivery")
        @JvmField val REDUCE_OPTIONAL_RETRIEVAL_DEPTH =
            CapacityDegradationCapability("reduce_optional_retrieval_depth")
    }
}
