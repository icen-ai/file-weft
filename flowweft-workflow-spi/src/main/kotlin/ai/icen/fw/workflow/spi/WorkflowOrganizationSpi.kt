package ai.icen.fw.workflow.spi

import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import java.util.concurrent.CompletionStage

/** Host-owned organization object reference; it is not a participant selector. */
class WorkflowOrganizationRef private constructor(type: String, id: String) {
    val type: String = WorkflowSpiContractSupport.requireMachineCode(type, "Workflow organization type is invalid.")
    val id: String = WorkflowSpiContractSupport.requireText(
        id, WorkflowSpiContractSupport.MAX_ID_UTF8_BYTES, "Workflow organization identifier is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowOrganizationRef && type == other.type && id == other.id

    override fun hashCode(): Int = 31 * type.hashCode() + id.hashCode()
    override fun toString(): String = "WorkflowOrganizationRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(type: String, id: String): WorkflowOrganizationRef = WorkflowOrganizationRef(type, id)
    }
}

/** Extensible relationship code. Unknown relationships require explicit authority support. */
class WorkflowOrganizationRelationshipKind private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(code, "Workflow organization relationship is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowOrganizationRelationshipKind && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowOrganizationRelationshipKind(<redacted>)"

    companion object {
        @JvmField val DIRECT_MEMBER = WorkflowOrganizationRelationshipKind("direct-member")
        @JvmField val EFFECTIVE_MEMBER = WorkflowOrganizationRelationshipKind("effective-member")
        @JvmField val DIRECT_MANAGER = WorkflowOrganizationRelationshipKind("direct-manager")
        @JvmField val EFFECTIVE_MANAGER = WorkflowOrganizationRelationshipKind("effective-manager")
        @JvmField val ACTIVE_DELEGATE = WorkflowOrganizationRelationshipKind("active-delegate")
        @JvmField val ASSIGNED_ROLE = WorkflowOrganizationRelationshipKind("assigned-role")
        @JvmField val HOLDS_POSITION = WorkflowOrganizationRelationshipKind("holds-position")
        /** Eligibility fact only; it never grants a Workflow action. */
        @JvmField val HAS_PERMISSION = WorkflowOrganizationRelationshipKind("has-permission")

        @JvmStatic
        fun of(code: String): WorkflowOrganizationRelationshipKind = when (code) {
            DIRECT_MEMBER.code -> DIRECT_MEMBER
            EFFECTIVE_MEMBER.code -> EFFECTIVE_MEMBER
            DIRECT_MANAGER.code -> DIRECT_MANAGER
            EFFECTIVE_MANAGER.code -> EFFECTIVE_MANAGER
            ACTIVE_DELEGATE.code -> ACTIVE_DELEGATE
            ASSIGNED_ROLE.code -> ASSIGNED_ROLE
            HOLDS_POSITION.code -> HOLDS_POSITION
            HAS_PERMISSION.code -> HAS_PERMISSION
            else -> WorkflowOrganizationRelationshipKind(code)
        }
    }
}

class WorkflowOrganizationSnapshotRequest private constructor(
    val context: WorkflowProviderCallContext,
) {
    val requestDigest: String = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-organization-snapshot-request-v1")
        .text(context.contextDigest)
        .finish()

    override fun toString(): String = "WorkflowOrganizationSnapshotRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(context: WorkflowProviderCallContext): WorkflowOrganizationSnapshotRequest =
            WorkflowOrganizationSnapshotRequest(context)
    }
}

/** Pinned directory revision used by participant resolution and later current-membership checks. */
class WorkflowOrganizationSnapshot private constructor(
    authorityId: String,
    revision: String,
    val capturedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val authorityId: String = WorkflowSpiContractSupport.requireMachineCode(
        authorityId, "Workflow organization authority is invalid.",
    )
    val revision: String = WorkflowSpiContractSupport.requireText(
        revision, WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES, "Workflow organization revision is invalid.",
    )
    val snapshotDigest: String

    init {
        require(capturedAtEpochMilli >= 0L && expiresAtEpochMilli > capturedAtEpochMilli) {
            "Workflow organization snapshot expiry must follow capture time."
        }
        snapshotDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-organization-snapshot-v1")
            .text(this.authorityId)
            .text(this.revision)
            .longValue(capturedAtEpochMilli)
            .longValue(expiresAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "WorkflowOrganizationSnapshot(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            authorityId: String,
            revision: String,
            capturedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowOrganizationSnapshot = WorkflowOrganizationSnapshot(
            authorityId,
            revision,
            capturedAtEpochMilli,
            expiresAtEpochMilli,
        )
    }
}

class WorkflowOrganizationSnapshotResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val snapshot: WorkflowOrganizationSnapshot?,
) {
    init {
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (snapshot != null)) {
            "Workflow organization snapshot result content does not match its outcome."
        }
    }

    override fun toString(): String = "WorkflowOrganizationSnapshotResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowOrganizationSnapshotRequest,
            snapshot: WorkflowOrganizationSnapshot,
            completedAtEpochMilli: Long,
        ): WorkflowOrganizationSnapshotResult {
            require(snapshot.authorityId == request.context.providerId) {
                "Workflow organization snapshot authority does not match the provider."
            }
            require(snapshot.capturedAtEpochMilli <= completedAtEpochMilli &&
                snapshot.expiresAtEpochMilli >= completedAtEpochMilli
            ) { "Workflow organization snapshot is not valid at provider completion." }
            return WorkflowOrganizationSnapshotResult(
                WorkflowProviderReceipt.success(
                    request.context,
                    request.requestDigest,
                    snapshot.snapshotDigest,
                    completedAtEpochMilli,
                    minOf(snapshot.expiresAtEpochMilli, request.context.deadlineEpochMilli),
                ),
                snapshot,
            )
        }

        @JvmStatic
        fun failure(
            request: WorkflowOrganizationSnapshotRequest,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowOrganizationSnapshotResult = WorkflowOrganizationSnapshotResult(
            WorkflowProviderReceipt.failure(
                request.context,
                request.requestDigest,
                outcome,
                WorkflowSpiContractSupport.failureDigest("flowweft-workflow-spi-organization-snapshot-failure-v1", failure),
                failure,
                completedAtEpochMilli,
                expiresAtEpochMilli,
            ),
            null,
        )
    }
}

/** Exact current-relationship check. It intentionally does not resolve selector candidate lists. */
class WorkflowOrganizationRelationshipRequest private constructor(
    val context: WorkflowProviderCallContext,
    val snapshot: WorkflowOrganizationSnapshot,
    val principal: WorkflowPrincipalRef,
    val organization: WorkflowOrganizationRef,
    val relationship: WorkflowOrganizationRelationshipKind,
    val effectiveAtEpochMilli: Long,
) {
    val requestDigest: String

    init {
        require(snapshot.authorityId == context.providerId) {
            "Workflow organization snapshot authority does not match the provider context."
        }
        require(relationship == WorkflowOrganizationRelationshipKind.DIRECT_MEMBER ||
            relationship == WorkflowOrganizationRelationshipKind.EFFECTIVE_MEMBER ||
            relationship == WorkflowOrganizationRelationshipKind.DIRECT_MANAGER ||
            relationship == WorkflowOrganizationRelationshipKind.EFFECTIVE_MANAGER ||
            relationship == WorkflowOrganizationRelationshipKind.ACTIVE_DELEGATE ||
            relationship == WorkflowOrganizationRelationshipKind.ASSIGNED_ROLE ||
            relationship == WorkflowOrganizationRelationshipKind.HOLDS_POSITION ||
            relationship == WorkflowOrganizationRelationshipKind.HAS_PERMISSION
        ) { "Unknown workflow organization relationships require future typed support." }
        require(effectiveAtEpochMilli in context.requestedAtEpochMilli..context.deadlineEpochMilli) {
            "Workflow organization relationship effective time is outside the call window."
        }
        require(effectiveAtEpochMilli in snapshot.capturedAtEpochMilli..snapshot.expiresAtEpochMilli) {
            "Workflow organization snapshot is not valid at the requested effective time."
        }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-organization-relationship-request-v1")
            .text(context.contextDigest)
            .text(snapshot.snapshotDigest)
            .text(principal.type)
            .text(principal.id)
            .text(organization.type)
            .text(organization.id)
            .text(relationship.code)
            .longValue(effectiveAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "WorkflowOrganizationRelationshipRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowProviderCallContext,
            snapshot: WorkflowOrganizationSnapshot,
            principal: WorkflowPrincipalRef,
            organization: WorkflowOrganizationRef,
            relationship: WorkflowOrganizationRelationshipKind,
            effectiveAtEpochMilli: Long,
        ): WorkflowOrganizationRelationshipRequest = WorkflowOrganizationRelationshipRequest(
            context,
            snapshot,
            principal,
            organization,
            relationship,
            effectiveAtEpochMilli,
        )
    }
}

class WorkflowOrganizationRelationshipDecision private constructor(
    val verified: Boolean,
    evidenceDigest: String,
) {
    val evidenceDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        evidenceDigest, "Workflow organization relationship evidence digest is invalid.",
    )
    val decisionDigest: String = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-organization-relationship-v1")
        .booleanValue(verified)
        .text(this.evidenceDigest)
        .finish()

    override fun toString(): String = "WorkflowOrganizationRelationshipDecision(<redacted>)"

    companion object {
        @JvmStatic
        fun of(verified: Boolean, evidenceDigest: String): WorkflowOrganizationRelationshipDecision =
            WorkflowOrganizationRelationshipDecision(verified, evidenceDigest)
    }
}

class WorkflowOrganizationRelationshipResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val decision: WorkflowOrganizationRelationshipDecision?,
) {
    init {
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (decision != null)) {
            "Workflow organization relationship result content does not match its outcome."
        }
    }

    override fun toString(): String = "WorkflowOrganizationRelationshipResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowOrganizationRelationshipRequest,
            decision: WorkflowOrganizationRelationshipDecision,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowOrganizationRelationshipResult = WorkflowOrganizationRelationshipResult(
            WorkflowProviderReceipt.success(
                request.context,
                request.requestDigest,
                decision.decisionDigest,
                completedAtEpochMilli,
                expiresAtEpochMilli,
            ),
            decision,
        )

        @JvmStatic
        fun failure(
            request: WorkflowOrganizationRelationshipRequest,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowOrganizationRelationshipResult = WorkflowOrganizationRelationshipResult(
            WorkflowProviderReceipt.failure(
                request.context,
                request.requestDigest,
                outcome,
                WorkflowSpiContractSupport.failureDigest("flowweft-workflow-spi-organization-relationship-failure-v1", failure),
                failure,
                completedAtEpochMilli,
                expiresAtEpochMilli,
            ),
            null,
        )
    }
}

interface WorkflowOrganizationAuthority {
    fun snapshot(request: WorkflowOrganizationSnapshotRequest): CompletionStage<WorkflowOrganizationSnapshotResult>
    fun verifyRelationship(
        request: WorkflowOrganizationRelationshipRequest,
    ): CompletionStage<WorkflowOrganizationRelationshipResult>
}
