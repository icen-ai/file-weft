package ai.icen.fw.capacity.runtime

import ai.icen.fw.capacity.api.CapacityDegradationCapability
import ai.icen.fw.capacity.api.CapacityPolicy
import ai.icen.fw.capacity.api.CapacityProviderSpi
import ai.icen.fw.capacity.api.CapacityScopeLevel
import ai.icen.fw.capacity.api.CapacityTrustedContext
import ai.icen.fw.capacity.api.ResourceScope
import ai.icen.fw.capacity.api.WorkloadKind
import ai.icen.fw.core.id.Identifier
import java.util.Collections

fun interface CapacityProviderRegistry {
    fun find(providerId: Identifier): CapacityProviderSpi?
}

/** Immutable process registry; explicit keys avoid a provider call during composition. */
class ImmutableCapacityProviderRegistry(providers: Map<Identifier, CapacityProviderSpi>) : CapacityProviderRegistry {
    private val providersById: Map<Identifier, CapacityProviderSpi>

    init {
        require(providers.size <= 128) { "Capacity provider registry is too large." }
        val snapshot = LinkedHashMap<Identifier, CapacityProviderSpi>()
        providers.forEach { (providerId, provider) ->
            val validated = requireRuntimeIdentifier(providerId, "Capacity provider registry identifier")
            require(snapshot.put(validated, provider) == null) {
                "Capacity provider registry contains a duplicate provider identifier."
            }
        }
        providersById = Collections.unmodifiableMap(snapshot)
    }

    override fun find(providerId: Identifier): CapacityProviderSpi? = providersById[providerId]
}

/** Trusted, payload-free policy lookup. Source implementations return policies, never repositories. */
class CapacityPolicySourceRequest(
    val context: CapacityTrustedContext,
    providerId: Identifier,
    val target: ResourceScope,
    val workload: WorkloadKind,
    val requestedAt: Long,
    val deadlineAt: Long,
) {
    val providerId: Identifier = requireRuntimeIdentifier(providerId, "Capacity policy provider identifier")
    val requestDigest: String

    init {
        context.requireFresh(requestedAt)
        require((target.level == CapacityScopeLevel.SYSTEM || target.tenantId == context.tenantId) &&
            context.authorizedScope.appliesTo(target)
        ) {
            "Capacity policy target is outside the trusted authorization scope."
        }
        require(deadlineAt > requestedAt && deadlineAt <= context.authorizationExpiresAt) {
            "Capacity policy lookup lifetime is invalid."
        }
        requestDigest = CapacityRuntimeDigest("flowweft.capacity.runtime.policy-source-request.v1")
            .add(context.bindingDigest)
            .add(this.providerId.value)
            .add(target.bindingDigest)
            .add(workload.value)
            .add(requestedAt)
            .add(deadlineAt)
            .finish()
    }

    override fun toString(): String = "CapacityPolicySourceRequest(workload=$workload, <redacted>)"
}

/**
 * Complete, time-bounded result from a policy source. [coveredLevels] proves that an empty
 * hierarchy layer was checked instead of silently omitted. Policies are exact candidates for
 * this request; returning unrelated tenants, workloads or scopes is a contract failure.
 */
class CapacityPolicySourceSnapshot(
    val request: CapacityPolicySourceRequest,
    policies: Collection<CapacityPolicy>,
    coveredLevels: Collection<CapacityScopeLevel>,
    sourceRevisionDigest: String,
    val observedAt: Long,
    val expiresAt: Long,
) {
    val policies: List<CapacityPolicy> = runtimeList(
        policies.sortedBy { policy -> policy.bindingDigest },
        CapacityRuntimeLimits.MAX_POLICY_CANDIDATES,
        "Capacity policy source policies",
    )
    val coveredLevels: Set<CapacityScopeLevel> = runtimeSet(
        coveredLevels,
        CapacityScopeLevel.values().size,
        "Capacity policy source covered levels",
    )
    val sourceRevisionDigest: String = requireRuntimeDigest(
        sourceRevisionDigest,
        "Capacity policy source revision",
    )
    val snapshotDigest: String

    init {
        require(observedAt in request.requestedAt until request.deadlineAt &&
            expiresAt >= request.deadlineAt
        ) { "Capacity policy source snapshot is stale for the requested operation." }
        require(this.policies.all { policy ->
            policy.isApplicableTo(request.target, request.workload, observedAt)
        }) { "Capacity policy source returned an unrelated or stale policy." }
        require(this.policies.map { policy -> policy.policyId }.toSet().size == this.policies.size) {
            "Capacity policy source returned overlapping policy revisions."
        }
        snapshotDigest = CapacityRuntimeDigest("flowweft.capacity.runtime.policy-source-snapshot.v1")
            .add(request.requestDigest)
            .add(this.sourceRevisionDigest)
            .add(observedAt)
            .add(expiresAt)
            .also { digest ->
                this.coveredLevels.sortedBy { level -> level.hierarchyRank }
                    .forEach { level -> digest.add(level.name) }
                this.policies.forEach { policy -> digest.add(policy.bindingDigest) }
            }
            .finish()
    }

    override fun toString(): String =
        "CapacityPolicySourceSnapshot(levels=${coveredLevels.size}, policies=${policies.size}, <redacted>)"
}

fun interface CapacityPolicySource {
    fun snapshot(request: CapacityPolicySourceRequest): CapacityPolicySourceSnapshot
}

/** Fixed source for embedded deployments and deterministic assembly. */
class FixedCapacityPolicySource(policies: Collection<CapacityPolicy>) : CapacityPolicySource {
    private val policies: List<CapacityPolicy> = runtimeList(
        policies,
        CapacityRuntimeLimits.MAX_POLICY_CANDIDATES,
        "Fixed capacity policies",
    )
    private val revisionDigest: String = CapacityRuntimeDigest("flowweft.capacity.runtime.fixed-policy-source.v1")
        .also { digest -> this.policies.sortedBy { policy -> policy.bindingDigest }
            .forEach { policy -> digest.add(policy.bindingDigest) } }
        .finish()

    override fun snapshot(request: CapacityPolicySourceRequest): CapacityPolicySourceSnapshot {
        val applicable = policies.filter { policy ->
            policy.isApplicableTo(request.target, request.workload, request.requestedAt)
        }
        return CapacityPolicySourceSnapshot(
            request,
            applicable,
            CapacityScopeLevel.values().toList(),
            revisionDigest,
            request.requestedAt,
            request.deadlineAt,
        )
    }
}

/** Fail-closed classifier: only explicitly capacity-only degradations may reach a provider. */
fun interface CapacityDegradationSafetyPolicy {
    fun isCapacityOnly(capability: CapacityDegradationCapability): Boolean

    companion object {
        @JvmField val DENY_ALL: CapacityDegradationSafetyPolicy = CapacityDegradationSafetyPolicy { false }

        @JvmField
        val STANDARD_CAPACITY_ONLY: CapacityDegradationSafetyPolicy = allowListed(
            setOf(
                CapacityDegradationCapability.DEFER_SECONDARY_INDEXING,
                CapacityDegradationCapability.REDUCE_OPTIONAL_ENRICHMENT,
                CapacityDegradationCapability.ASYNC_CONNECTOR_DELIVERY,
                CapacityDegradationCapability.REDUCE_OPTIONAL_RETRIEVAL_DEPTH,
            ),
        )

        @JvmStatic
        fun allowListed(capabilities: Collection<CapacityDegradationCapability>): CapacityDegradationSafetyPolicy {
            val allowed = runtimeSet(
                capabilities,
                CapacityRuntimeLimits.MAX_COMMAND_ITEMS,
                "Safe capacity degradations",
            )
            return CapacityDegradationSafetyPolicy { capability -> capability in allowed }
        }
    }
}

/** One signal that a host transaction adapter schedules only after successful completion. */
fun interface CapacityDeferredSignal {
    fun emit()
}

fun interface CapacityAfterCommitSignalPort {
    /** Schedule after the current transaction ends; if none exists, invoke only after the provider call returned. */
    fun afterCommit(signal: CapacityDeferredSignal)

    companion object {
        @JvmField val DISCARD: CapacityAfterCommitSignalPort = CapacityAfterCommitSignalPort { }
    }
}

/**
 * Mandatory guard for every policy/provider call. Spring/JTA adapters must throw when a database
 * transaction is active. The explicit unmanaged option is only for hosts that can prove there is
 * no ambient transaction facility (for example a command-line process).
 */
fun interface CapacityExternalCallBoundary {
    fun requireOutsideTransaction(operationCode: String)

    companion object {
        @JvmField
        val UNMANAGED_NON_TRANSACTIONAL: CapacityExternalCallBoundary = CapacityExternalCallBoundary { operation ->
            requireRuntimeCode(operation, "Capacity external operation")
        }
    }
}
