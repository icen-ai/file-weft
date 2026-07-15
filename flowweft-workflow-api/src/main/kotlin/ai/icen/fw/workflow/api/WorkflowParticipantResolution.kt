package ai.icen.fw.workflow.api

import java.util.ArrayList

/**
 * Bounded response DTO from a configured organization resolver invocation.
 *
 * A valid digest proves only internal field consistency. This caller-constructible object is not an
 * authorization decision, task claim, assignment permit, or proof that its authority produced it.
 * The runtime must match the actual registered resolver call, exact request digest, tenant,
 * authority, revision and time window, then perform fresh business authorization when a principal
 * claims or completes work.
 *
 * A resolved response contains one or more consecutive tiers for every selector, grouped in the
 * exact selector order from the request. The flattened [principals] list is an ordered occurrence
 * projection: the same principal remains present when independently selected by different tiers or
 * selectors. A tier still rejects its own duplicates. Runtime task policy decides whether and how
 * occurrences contribute to one/all/quorum semantics; this DTO never destroys resolution facts.
 */
class WorkflowParticipantResolution private constructor(
    request: WorkflowParticipantResolutionRequest,
    val status: WorkflowParticipantResolutionStatus,
    tiers: List<WorkflowParticipantTier>,
    val reason: WorkflowParticipantResolutionReason?,
    val retryable: Boolean,
    val resolvedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val requestId: String = request.requestId
    val requestDigest: String = request.requestDigest
    val tenantId: String = request.tenantId
    val authority: String = request.organizationAuthority
    val authorityRevision: String = request.organizationSnapshotRevision
    val tiers: List<WorkflowParticipantTier> = WorkflowContractSupport.immutableList(
        tiers,
        WorkflowContractSupport.MAX_TIERS,
        "Workflow participant resolution tiers are invalid or exceed the limit.",
    )
    val principals: List<WorkflowPrincipalRef>
    val resolutionDigest: String

    init {
        require(
            resolvedAtEpochMilli >= request.requestedAtEpochMilli &&
                resolvedAtEpochMilli < request.deadlineEpochMilli,
        ) { "Workflow participant resolution time is outside its request window." }
        require(expiresAtEpochMilli > resolvedAtEpochMilli && expiresAtEpochMilli <= request.deadlineEpochMilli) {
            "Workflow participant resolution expiry is outside its request window."
        }

        if (status == WorkflowParticipantResolutionStatus.RESOLVED) {
            require(reason == null && !retryable) {
                "Resolved workflow participant responses cannot carry failure metadata."
            }
            principals = validateResolvedTiers(request)
        } else {
            require(this.tiers.isEmpty()) { "Unresolved workflow participant responses cannot carry tiers." }
            require(reason != null) { "Unresolved workflow participant responses require a reason code." }
            require(
                status == WorkflowParticipantResolutionStatus.ERROR ||
                    (status != WorkflowParticipantResolutionStatus.EMPTY &&
                        status != WorkflowParticipantResolutionStatus.DENIED) ||
                    !retryable,
            ) { "Empty or denied workflow participant responses cannot be retryable." }
            principals = emptyList()
        }

        val writer = WorkflowContractSupport.digest(WorkflowContractSupport.RESOLUTION_DIGEST_DOMAIN)
            .text(requestId)
            .text(requestDigest)
            .text(tenantId)
            .text(status.code)
            .text(authority)
            .text(authorityRevision)
            .integer(this.tiers.size)
        this.tiers.forEach { tier -> writer.text(tier.digest) }
        writer.integer(principals.size)
        principals.forEach { principal -> writer.text(principal.type).text(principal.id) }
        writer.optionalText(reason?.code)
            .booleanValue(retryable)
            .longValue(resolvedAtEpochMilli)
            .longValue(expiresAtEpochMilli)
        resolutionDigest = writer.finish()
    }

    private fun validateResolvedTiers(
        request: WorkflowParticipantResolutionRequest,
    ): List<WorkflowPrincipalRef> {
        require(tiers.isNotEmpty()) { "Resolved workflow participant responses require tiers." }
        tiers.forEachIndexed { index, tier ->
            require(tier.tierIndex == index) {
                "Workflow participant tier indexes must be contiguous and start at zero."
            }
        }

        val flattened = ArrayList<WorkflowPrincipalRef>()
        var tierCursor = 0
        request.selectors.forEach { selector ->
            require(tierCursor < tiers.size && tiers[tierCursor].selectorDigest == selector.digest) {
                "Every workflow participant selector must resolve in request order."
            }

            var tierCount = 0
            val selectorPrincipalStart = flattened.size
            var expectedManagerLevel = selector.minimumManagerLevel
            while (tierCursor < tiers.size && tiers[tierCursor].selectorDigest == selector.digest) {
                val tier = tiers[tierCursor]
                if (expectedManagerLevel != null) {
                    require(tier.managerLevel == expectedManagerLevel) {
                        "Workflow manager levels must be contiguous and start at the selector minimum."
                    }
                    expectedManagerLevel += 1
                } else {
                    require(tier.managerLevel == null) {
                        "Non-manager workflow selectors cannot produce manager levels."
                    }
                }

                tier.principals.forEach { principal ->
                    flattened.add(principal)
                }
                require(flattened.size <= request.maximumPrincipals) {
                    "Workflow participant resolution exceeds its requested principal limit."
                }
                tierCursor += 1
                tierCount += 1
            }
            require(tierCount > 0) { "Resolved workflow participant selectors must not be empty." }
            if (
                selector.kind == WorkflowParticipantSelectorKind.EXACT_USER &&
                request.delegationPolicy.mode == WorkflowDelegationMode.DISABLED
            ) {
                require(
                    tierCount == 1 &&
                        flattened.size == selectorPrincipalStart + 1 &&
                        flattened[selectorPrincipalStart] == selector.exactPrincipal,
                ) { "Exact-user workflow selectors must resolve to that user when delegation is disabled." }
            }
        }
        require(tierCursor == tiers.size) { "Workflow participant tiers contain an unrequested selector." }
        return WorkflowContractSupport.immutableList(
            flattened,
            request.maximumPrincipals,
            "Workflow participant resolution principals are invalid or exceed the limit.",
        )
    }

    override fun toString(): String = "WorkflowParticipantResolution(<redacted>)"

    companion object {
        @JvmStatic
        fun resolved(
            request: WorkflowParticipantResolutionRequest,
            tiers: List<WorkflowParticipantTier>,
            resolvedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowParticipantResolution = WorkflowParticipantResolution(
            request,
            WorkflowParticipantResolutionStatus.RESOLVED,
            tiers,
            null,
            false,
            resolvedAtEpochMilli,
            expiresAtEpochMilli,
        )

        @JvmStatic
        fun empty(
            request: WorkflowParticipantResolutionRequest,
            reason: WorkflowParticipantResolutionReason,
            resolvedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowParticipantResolution = unresolved(
            request,
            WorkflowParticipantResolutionStatus.EMPTY,
            reason,
            false,
            resolvedAtEpochMilli,
            expiresAtEpochMilli,
        )

        /** Directory disclosure denial; this is not a business authorization decision. */
        @JvmStatic
        fun denied(
            request: WorkflowParticipantResolutionRequest,
            reason: WorkflowParticipantResolutionReason,
            resolvedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowParticipantResolution = unresolved(
            request,
            WorkflowParticipantResolutionStatus.DENIED,
            reason,
            false,
            resolvedAtEpochMilli,
            expiresAtEpochMilli,
        )

        @JvmStatic
        fun error(
            request: WorkflowParticipantResolutionRequest,
            reason: WorkflowParticipantResolutionReason,
            retryable: Boolean,
            resolvedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowParticipantResolution = unresolved(
            request,
            WorkflowParticipantResolutionStatus.ERROR,
            reason,
            retryable,
            resolvedAtEpochMilli,
            expiresAtEpochMilli,
        )

        /** Extension outcomes are unresolved and must remain fail-closed until runtime support exists. */
        @JvmStatic
        fun unresolved(
            request: WorkflowParticipantResolutionRequest,
            status: WorkflowParticipantResolutionStatus,
            reason: WorkflowParticipantResolutionReason,
            retryable: Boolean,
            resolvedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowParticipantResolution {
            require(status != WorkflowParticipantResolutionStatus.RESOLVED) {
                "Resolved workflow participant responses require explicit tiers."
            }
            return WorkflowParticipantResolution(
                request,
                status,
                emptyList(),
                reason,
                retryable,
                resolvedAtEpochMilli,
                expiresAtEpochMilli,
            )
        }
    }
}
