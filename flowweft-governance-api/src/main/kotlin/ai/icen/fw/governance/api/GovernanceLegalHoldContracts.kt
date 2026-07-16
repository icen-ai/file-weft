package ai.icen.fw.governance.api

class GovernanceLegalHoldScopeType private constructor(code: String) {
    val code: String = GovernanceContractSupport.requireMachineCode(
        code, "Governance legal-hold scope type is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is GovernanceLegalHoldScopeType && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceLegalHoldScopeType(<redacted>)"

    companion object {
        @JvmField val RESOURCE = GovernanceLegalHoldScopeType("resource")
        @JvmField val RESOURCE_CLASS = GovernanceLegalHoldScopeType("resource-class")
        @JvmField val TENANT = GovernanceLegalHoldScopeType("tenant")

        @JvmStatic
        fun of(code: String): GovernanceLegalHoldScopeType = when (code) {
            RESOURCE.code -> RESOURCE
            RESOURCE_CLASS.code -> RESOURCE_CLASS
            TENANT.code -> TENANT
            else -> GovernanceLegalHoldScopeType(code)
        }
    }
}

/** Versioned host-owned scope. The resolver, not this value, determines applicability. */
class GovernanceLegalHoldScope private constructor(
    tenantId: String,
    val type: GovernanceLegalHoldScopeType,
    scopeRef: String,
    revision: String,
    digest: String,
) {
    val tenantId: String = GovernanceContractSupport.requireText(
        tenantId, GovernanceContractSupport.MAX_ID_UTF8_BYTES, "Governance legal-hold scope tenant is invalid.",
    )
    val scopeRef: String = GovernanceContractSupport.requireOpaqueReference(
        scopeRef, "Governance legal-hold scope reference is invalid.",
    )
    val revision: String = GovernanceContractSupport.requireText(
        revision, GovernanceContractSupport.MAX_REVISION_UTF8_BYTES,
        "Governance legal-hold scope revision is invalid.",
    )
    val digest: String = GovernanceContractSupport.requireSha256(
        digest, "Governance legal-hold scope digest is invalid.",
    )
    val scopeDigest: String = GovernanceContractSupport.digest("flowweft-governance-api-hold-scope-v1")
        .text(this.tenantId)
        .text(type.code)
        .text(this.scopeRef)
        .text(this.revision)
        .text(this.digest)
        .finish()

    override fun equals(other: Any?): Boolean = this === other ||
        other is GovernanceLegalHoldScope && tenantId == other.tenantId && type == other.type &&
        scopeRef == other.scopeRef && revision == other.revision && digest == other.digest

    override fun hashCode(): Int {
        var result = tenantId.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + scopeRef.hashCode()
        result = 31 * result + revision.hashCode()
        result = 31 * result + digest.hashCode()
        return result
    }

    override fun toString(): String = "GovernanceLegalHoldScope(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: String,
            type: GovernanceLegalHoldScopeType,
            scopeRef: String,
            revision: String,
            digest: String,
        ): GovernanceLegalHoldScope = GovernanceLegalHoldScope(tenantId, type, scopeRef, revision, digest)
    }
}

/** Immutable proof that an exact hold revision was intentionally released by an authorized principal. */
class GovernanceLegalHoldReleaseEvidence private constructor(
    releaseId: String,
    holdId: String,
    val releasedBy: GovernancePrincipalRef,
    authorizationRevision: String,
    decisionDigest: String,
    reasonCode: String,
    val releasedAtEpochMilli: Long,
) {
    val releaseId: String = GovernanceContractSupport.requireOpaqueReference(
        releaseId, "Governance legal-hold release identifier is invalid.",
    )
    val holdId: String = GovernanceContractSupport.requireOpaqueReference(
        holdId, "Governance legal-hold release hold identifier is invalid.",
    )
    val authorizationRevision: String = GovernanceContractSupport.requireText(
        authorizationRevision, GovernanceContractSupport.MAX_REVISION_UTF8_BYTES,
        "Governance legal-hold release authorization revision is invalid.",
    )
    val decisionDigest: String = GovernanceContractSupport.requireSha256(
        decisionDigest, "Governance legal-hold release decision digest is invalid.",
    )
    val reasonCode: String = GovernanceContractSupport.requireMachineCode(
        reasonCode, "Governance legal-hold release reason is invalid.",
    )
    val evidenceDigest: String

    init {
        require(releasedAtEpochMilli >= 0L) { "Governance legal-hold release time is invalid." }
        evidenceDigest = GovernanceContractSupport.digest("flowweft-governance-api-hold-release-v1")
            .text(this.releaseId)
            .text(this.holdId)
            .text(releasedBy.type)
            .text(releasedBy.id)
            .text(this.authorizationRevision)
            .text(this.decisionDigest)
            .text(this.reasonCode)
            .longValue(releasedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "GovernanceLegalHoldReleaseEvidence(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            releaseId: String,
            holdId: String,
            releasedBy: GovernancePrincipalRef,
            authorizationRevision: String,
            decisionDigest: String,
            reasonCode: String,
            releasedAtEpochMilli: Long,
        ): GovernanceLegalHoldReleaseEvidence = GovernanceLegalHoldReleaseEvidence(
            releaseId,
            holdId,
            releasedBy,
            authorizationRevision,
            decisionDigest,
            reasonCode,
            releasedAtEpochMilli,
        )
    }
}

class GovernanceLegalHoldStatus private constructor(code: String) {
    val code: String = GovernanceContractSupport.requireMachineCode(
        code, "Governance legal-hold status is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is GovernanceLegalHoldStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceLegalHoldStatus(<redacted>)"

    companion object {
        @JvmField val ACTIVE = GovernanceLegalHoldStatus("active")
        @JvmField val RELEASED = GovernanceLegalHoldStatus("released")

        @JvmStatic
        fun of(code: String): GovernanceLegalHoldStatus = when (code) {
            ACTIVE.code -> ACTIVE
            RELEASED.code -> RELEASED
            else -> GovernanceLegalHoldStatus(code)
        }
    }
}

/** Immutable hold snapshot. Active holds intentionally cannot contain release evidence. */
class GovernanceLegalHoldSnapshot private constructor(
    holdId: String,
    tenantId: String,
    val scope: GovernanceLegalHoldScope,
    val priority: Int,
    revision: String,
    digest: String,
    val status: GovernanceLegalHoldStatus,
    val appliedAtEpochMilli: Long,
    val releaseEvidence: GovernanceLegalHoldReleaseEvidence?,
) {
    val holdId: String = GovernanceContractSupport.requireOpaqueReference(
        holdId, "Governance legal-hold identifier is invalid.",
    )
    val tenantId: String = GovernanceContractSupport.requireText(
        tenantId, GovernanceContractSupport.MAX_ID_UTF8_BYTES, "Governance legal-hold tenant is invalid.",
    )
    val revision: String = GovernanceContractSupport.requireText(
        revision, GovernanceContractSupport.MAX_REVISION_UTF8_BYTES,
        "Governance legal-hold revision is invalid.",
    )
    val digest: String = GovernanceContractSupport.requireSha256(
        digest, "Governance legal-hold digest is invalid.",
    )
    val snapshotDigest: String

    init {
        require(scope.tenantId == this.tenantId) { "Governance legal-hold scope tenant does not match." }
        require(priority in 0..1_000_000) { "Governance legal-hold priority is invalid." }
        require(appliedAtEpochMilli >= 0L) { "Governance legal-hold application time is invalid." }
        when (status) {
            GovernanceLegalHoldStatus.ACTIVE -> require(releaseEvidence == null) {
                "An active governance legal hold cannot carry release evidence."
            }
            GovernanceLegalHoldStatus.RELEASED -> require(
                releaseEvidence?.holdId == this.holdId && releaseEvidence.releasedAtEpochMilli >= appliedAtEpochMilli,
            ) { "A released governance legal hold requires matching release evidence." }
            else -> require(false) { "Unknown governance legal-hold status is fail-closed." }
        }
        snapshotDigest = GovernanceContractSupport.digest("flowweft-governance-api-hold-snapshot-v1")
            .text(this.holdId)
            .text(this.tenantId)
            .text(scope.scopeDigest)
            .integer(priority)
            .text(this.revision)
            .text(this.digest)
            .text(status.code)
            .longValue(appliedAtEpochMilli)
            .optionalText(releaseEvidence?.evidenceDigest)
            .finish()
    }

    override fun toString(): String = "GovernanceLegalHoldSnapshot(<redacted>)"

    companion object {
        @JvmStatic
        fun active(
            holdId: String,
            tenantId: String,
            scope: GovernanceLegalHoldScope,
            priority: Int,
            revision: String,
            digest: String,
            appliedAtEpochMilli: Long,
        ): GovernanceLegalHoldSnapshot = GovernanceLegalHoldSnapshot(
            holdId,
            tenantId,
            scope,
            priority,
            revision,
            digest,
            GovernanceLegalHoldStatus.ACTIVE,
            appliedAtEpochMilli,
            null,
        )

        @JvmStatic
        fun released(
            holdId: String,
            tenantId: String,
            scope: GovernanceLegalHoldScope,
            priority: Int,
            revision: String,
            digest: String,
            appliedAtEpochMilli: Long,
            releaseEvidence: GovernanceLegalHoldReleaseEvidence,
        ): GovernanceLegalHoldSnapshot = GovernanceLegalHoldSnapshot(
            holdId,
            tenantId,
            scope,
            priority,
            revision,
            digest,
            GovernanceLegalHoldStatus.RELEASED,
            appliedAtEpochMilli,
            releaseEvidence,
        )
    }
}

class GovernanceLegalHoldResolutionStatus private constructor(code: String) {
    val code: String = GovernanceContractSupport.requireMachineCode(
        code, "Governance legal-hold resolution status is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is GovernanceLegalHoldResolutionStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceLegalHoldResolutionStatus(<redacted>)"

    companion object {
        @JvmField val HELD = GovernanceLegalHoldResolutionStatus("held")
        @JvmField val CLEAR = GovernanceLegalHoldResolutionStatus("clear")
        @JvmField val UNKNOWN = GovernanceLegalHoldResolutionStatus("unknown")
    }
}

/** Complete, resource-bound hold resolution. A known active hold wins even if other lookups are incomplete. */
class GovernanceLegalHoldResolution private constructor(
    val resource: GovernanceResourceRef,
    tenantId: String,
    authorityId: String,
    authorityRevision: String,
    val clock: GovernanceEffectiveClock,
    holds: Collection<GovernanceLegalHoldSnapshot>,
    val complete: Boolean,
    val status: GovernanceLegalHoldResolutionStatus,
    val failure: GovernanceFailure?,
    val expiresAtEpochMilli: Long,
) {
    val tenantId: String = GovernanceContractSupport.requireText(
        tenantId, GovernanceContractSupport.MAX_ID_UTF8_BYTES,
        "Governance legal-hold resolution tenant is invalid.",
    )
    val authorityId: String = GovernanceContractSupport.requireMachineCode(
        authorityId, "Governance legal-hold resolution authority is invalid.",
    )
    val authorityRevision: String = GovernanceContractSupport.requireText(
        authorityRevision, GovernanceContractSupport.MAX_REVISION_UTF8_BYTES,
        "Governance legal-hold resolution authority revision is invalid.",
    )
    val holds: List<GovernanceLegalHoldSnapshot> = GovernanceContractSupport.immutableList(
        GovernanceContractSupport.immutableList(
            holds,
            GovernanceContractSupport.MAX_HOLDS,
            "Governance legal-hold resolution entries are invalid.",
        ).sortedWith(compareByDescending<GovernanceLegalHoldSnapshot> { it.priority }.thenBy { it.holdId }),
        GovernanceContractSupport.MAX_HOLDS,
        "Governance legal-hold resolution entries are invalid.",
    )
    val activeHoldIds: List<String> = GovernanceContractSupport.immutableList(
        this.holds.filter { it.status == GovernanceLegalHoldStatus.ACTIVE }.map { it.holdId },
        GovernanceContractSupport.MAX_HOLDS,
        "Governance active legal-hold identifiers are invalid.",
    )
    val highestActivePriority: Int? = this.holds
        .filter { it.status == GovernanceLegalHoldStatus.ACTIVE }
        .maxOfOrNull { it.priority }
    val resolutionDigest: String

    init {
        require(expiresAtEpochMilli > clock.observedAtEpochMilli && expiresAtEpochMilli <= clock.expiresAtEpochMilli) {
            "Governance legal-hold resolution expiry is invalid."
        }
        require(this.holds.map { it.holdId }.toSet().size == this.holds.size) {
            "Governance legal-hold resolution identifiers must be unique."
        }
        require(this.holds.all {
            it.tenantId == this.tenantId && it.appliedAtEpochMilli <= clock.observedAtEpochMilli &&
                (it.releaseEvidence == null || it.releaseEvidence.releasedAtEpochMilli <= clock.observedAtEpochMilli)
        }) {
            "Governance legal-hold resolution contains mismatched or future evidence."
        }
        when (status) {
            GovernanceLegalHoldResolutionStatus.HELD -> require(activeHoldIds.isNotEmpty() && failure == null) {
                "Held governance resolution requires active hold evidence."
            }
            GovernanceLegalHoldResolutionStatus.CLEAR -> require(
                complete && activeHoldIds.isEmpty() && failure == null,
            ) { "Clear governance resolution requires complete evidence and no active hold." }
            GovernanceLegalHoldResolutionStatus.UNKNOWN -> require(
                !complete && activeHoldIds.isEmpty() && failure != null,
            ) { "Unknown governance resolution requires incomplete, value-free failure evidence." }
            else -> require(false) { "Unknown governance legal-hold resolution is fail-closed." }
        }
        val writer = GovernanceContractSupport.digest("flowweft-governance-api-hold-resolution-v1")
            .text(resource.referenceDigest)
            .text(this.tenantId)
            .text(this.authorityId)
            .text(this.authorityRevision)
            .text(clock.clockDigest)
            .booleanValue(complete)
            .text(status.code)
            .optionalText(failure?.failureDigest)
            .longValue(expiresAtEpochMilli)
            .integer(this.holds.size)
        this.holds.forEach { hold -> writer.text(hold.snapshotDigest) }
        resolutionDigest = writer.finish()
    }

    override fun toString(): String = "GovernanceLegalHoldResolution(<redacted>)"

    companion object {
        @JvmStatic
        fun held(
            resource: GovernanceResourceRef,
            tenantId: String,
            authorityId: String,
            authorityRevision: String,
            clock: GovernanceEffectiveClock,
            holds: Collection<GovernanceLegalHoldSnapshot>,
            complete: Boolean,
            expiresAtEpochMilli: Long,
        ): GovernanceLegalHoldResolution = GovernanceLegalHoldResolution(
            resource,
            tenantId,
            authorityId,
            authorityRevision,
            clock,
            holds,
            complete,
            GovernanceLegalHoldResolutionStatus.HELD,
            null,
            expiresAtEpochMilli,
        )

        @JvmStatic
        fun clear(
            resource: GovernanceResourceRef,
            tenantId: String,
            authorityId: String,
            authorityRevision: String,
            clock: GovernanceEffectiveClock,
            releasedHolds: Collection<GovernanceLegalHoldSnapshot>,
            expiresAtEpochMilli: Long,
        ): GovernanceLegalHoldResolution = GovernanceLegalHoldResolution(
            resource,
            tenantId,
            authorityId,
            authorityRevision,
            clock,
            releasedHolds,
            true,
            GovernanceLegalHoldResolutionStatus.CLEAR,
            null,
            expiresAtEpochMilli,
        )

        @JvmStatic
        fun unknown(
            resource: GovernanceResourceRef,
            tenantId: String,
            authorityId: String,
            authorityRevision: String,
            clock: GovernanceEffectiveClock,
            failure: GovernanceFailure,
            expiresAtEpochMilli: Long,
        ): GovernanceLegalHoldResolution = GovernanceLegalHoldResolution(
            resource,
            tenantId,
            authorityId,
            authorityRevision,
            clock,
            emptyList(),
            false,
            GovernanceLegalHoldResolutionStatus.UNKNOWN,
            failure,
            expiresAtEpochMilli,
        )
    }
}
