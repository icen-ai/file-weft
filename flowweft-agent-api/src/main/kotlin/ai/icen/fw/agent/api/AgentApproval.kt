package ai.icen.fw.agent.api

import ai.icen.fw.core.id.Identifier

/**
 * One operator-bound approval challenge. It contains only safe identifiers and digests. Canonical
 * arguments remain outside approval storage, and secrets must already be server-side references.
 */
class AgentApprovalRequest private constructor(
    requestId: Identifier,
    proposalId: Identifier,
    policyDecisionId: Identifier,
    val policyProviderId: ProviderId,
    policyInputDigest: String,
    policyRevision: String,
    val policyExpiresAt: Long,
    val policyOutcome: AgentPolicyOutcome,
    tenantId: Identifier,
    principalId: Identifier,
    principalType: String,
    runId: Identifier,
    stepId: Identifier,
    val toolProviderId: ProviderId,
    val toolId: ToolId,
    descriptorDigest: String,
    schemaDigest: String,
    argumentsDigest: String,
    idempotencyKeyDigest: String,
    authorizationRequestId: Identifier,
    executionContextId: Identifier,
    val authorizationProviderId: ProviderId,
    authorizationDecisionId: Identifier,
    authorizationBindingDigest: String,
    authorizationRevision: String,
    val authorizationRequestExpiresAt: Long,
    val authorizationExpiresAt: Long,
    authorizationAction: String,
    authorizationResourceType: String,
    authorizationResourceId: Identifier,
    authorizationResourceRevision: String,
    authorizationPurpose: String,
    operatorId: Identifier,
    operatorType: String,
    nonce: String,
    val requestedAt: Long,
    val expiresAt: Long,
    expectedEvidenceDigest: String?,
) {
    val requestId: Identifier = requireOpaqueIdentifier(requestId, "Agent approval request identifier is invalid.")
    val proposalId: Identifier = requireOpaqueIdentifier(proposalId, "Agent approval proposal identifier is invalid.")
    val policyDecisionId: Identifier = requireOpaqueIdentifier(
        policyDecisionId,
        "Agent approval policy decision identifier is invalid.",
    )
    val policyInputDigest: String = requireSha256(policyInputDigest, "Agent approval policy input digest is invalid.")
    val policyRevision: String = requireAgentToken(
        policyRevision,
        AgentContractLimits.MAX_ID_CODE_POINTS,
        "Agent approval policy revision is invalid.",
    )
    val tenantId: Identifier = requireOpaqueIdentifier(tenantId, "Agent approval tenant identifier is invalid.")
    val principalId: Identifier = requireOpaqueIdentifier(principalId, "Agent approval principal identifier is invalid.")
    val principalType: String = requireAgentCode(principalType, "Agent approval principal type is invalid.")
    val runId: Identifier = requireOpaqueIdentifier(runId, "Agent approval run identifier is invalid.")
    val stepId: Identifier = requireOpaqueIdentifier(stepId, "Agent approval step identifier is invalid.")
    val descriptorDigest: String = requireSha256(descriptorDigest, "Agent approval descriptor digest is invalid.")
    val schemaDigest: String = requireSha256(schemaDigest, "Agent approval schema digest is invalid.")
    val argumentsDigest: String = requireSha256(argumentsDigest, "Agent approval arguments digest is invalid.")
    val idempotencyKeyDigest: String = requireSha256(
        idempotencyKeyDigest,
        "Agent approval idempotency digest is invalid.",
    )
    val authorizationRequestId: Identifier = requireOpaqueIdentifier(
        authorizationRequestId,
        "Agent approval authorization request identifier is invalid.",
    )
    val executionContextId: Identifier = requireOpaqueIdentifier(
        executionContextId,
        "Agent approval execution context identifier is invalid.",
    )
    val authorizationDecisionId: Identifier = requireOpaqueIdentifier(
        authorizationDecisionId,
        "Agent approval authorization decision identifier is invalid.",
    )
    val authorizationBindingDigest: String = requireSha256(
        authorizationBindingDigest,
        "Agent approval authorization binding digest is invalid.",
    )
    val authorizationRevision: String = requireAgentToken(
        authorizationRevision,
        AgentContractLimits.MAX_ID_CODE_POINTS,
        "Agent approval authorization revision is invalid.",
    )
    val authorizationAction: String = requireStableAgentId(
        authorizationAction,
        "Agent approval authorization action is invalid.",
    )
    val authorizationResourceType: String = requireStableAgentId(
        authorizationResourceType,
        "Agent approval authorization resource type is invalid.",
    )
    val authorizationResourceId: Identifier = requireOpaqueIdentifier(
        authorizationResourceId,
        "Agent approval authorization resource identifier is invalid.",
    )
    val authorizationResourceRevision: String = requireAgentToken(
        authorizationResourceRevision,
        AgentContractLimits.MAX_ID_CODE_POINTS,
        "Agent approval authorization resource revision is invalid.",
    )
    val authorizationPurpose: String = requireAgentToken(
        authorizationPurpose,
        AgentContractLimits.MAX_ID_CODE_POINTS,
        "Agent approval authorization purpose is invalid.",
    )
    val operatorId: Identifier = requireOpaqueIdentifier(operatorId, "Agent approval operator identifier is invalid.")
    val operatorType: String = requireAgentCode(operatorType, "Agent approval operator type is invalid.")
    val nonce: String = requireStableAgentId(nonce, "Agent approval nonce is invalid.")
    val evidenceDigest: String

    init {
        require(policyOutcome == AgentPolicyOutcome.REQUIRE_APPROVAL) {
            "Agent approval requests require a REQUIRE_APPROVAL policy decision."
        }
        requireNonNegativeTime(requestedAt, "Agent approval request time must not be negative.")
        require(
            expiresAt > requestedAt && expiresAt <= policyExpiresAt &&
                expiresAt <= authorizationRequestExpiresAt && expiresAt <= authorizationExpiresAt,
        ) { "Agent approval expiry must follow request time and remain within policy and authorization validity." }
        val digest = AgentDigestBuilder("flowweft.agent.approval-request-evidence.v1")
            .add(this.requestId.value)
            .add(this.proposalId.value)
            .add(this.policyDecisionId.value)
            .add(policyProviderId.value)
            .add(this.policyInputDigest)
            .add(this.policyRevision)
            .add(policyExpiresAt)
            .add(policyOutcome.name)
            .add(this.tenantId.value)
            .add(this.principalType)
            .add(this.principalId.value)
            .add(this.runId.value)
            .add(this.stepId.value)
            .add(toolProviderId.value)
            .add(toolId.value)
            .add(this.descriptorDigest)
            .add(this.schemaDigest)
            .add(this.argumentsDigest)
            .add(this.idempotencyKeyDigest)
            .add(this.authorizationRequestId.value)
            .add(this.executionContextId.value)
            .add(authorizationProviderId.value)
            .add(this.authorizationDecisionId.value)
            .add(this.authorizationBindingDigest)
            .add(this.authorizationRevision)
            .add(authorizationRequestExpiresAt)
            .add(authorizationExpiresAt)
            .add(this.authorizationAction)
            .add(this.authorizationResourceType)
            .add(this.authorizationResourceId.value)
            .add(this.authorizationResourceRevision)
            .add(this.authorizationPurpose)
            .add(this.operatorId.value)
            .add(this.operatorType)
            .add(this.nonce)
            .add(requestedAt)
            .add(expiresAt)
            .finish()
        if (expectedEvidenceDigest != null) {
            requireSha256(expectedEvidenceDigest, "Stored Agent approval evidence digest is invalid.")
            require(digest == expectedEvidenceDigest) {
                "Stored Agent approval evidence digest does not match its fields."
            }
        }
        evidenceDigest = digest
    }

    fun requireValidFor(
        proposal: AgentPolicyProposal,
        policyDecision: AgentPolicyDecision,
        atTime: Long,
    ) {
        policyDecision.requireValidFor(proposal, atTime)
        require(policyDecision.outcome == AgentPolicyOutcome.REQUIRE_APPROVAL) {
            "Agent approval request no longer has an approval-requiring policy decision."
        }
        require(
            proposalId == proposal.proposalId &&
                policyDecisionId == policyDecision.decisionId &&
                policyProviderId == policyDecision.providerId &&
                policyInputDigest == policyDecision.policyInputDigest &&
                policyRevision == policyDecision.policyRevision &&
                policyExpiresAt == policyDecision.expiresAt,
        ) { "Agent approval request policy binding does not match." }
        require(
            tenantId == proposal.tenantId &&
                principalId == proposal.principalId &&
                principalType == proposal.principalType &&
                runId == proposal.runId &&
                stepId == proposal.stepId,
        ) { "Agent approval request context does not match." }
        require(
            toolProviderId == proposal.toolProviderId &&
                toolId == proposal.toolId &&
                descriptorDigest == proposal.descriptorDigest &&
                schemaDigest == proposal.schemaDigest &&
                argumentsDigest == proposal.argumentsDigest &&
                idempotencyKeyDigest == proposal.idempotencyKeyDigest,
        ) { "Agent approval request tool, schema, arguments, or idempotency binding does not match." }
        require(
            authorizationRequestId == proposal.authorizationRequestId &&
                executionContextId == proposal.executionContextId &&
                authorizationProviderId == proposal.authorizationProviderId &&
                authorizationDecisionId == proposal.authorizationDecisionId &&
                authorizationBindingDigest == proposal.authorizationBindingDigest &&
                authorizationRevision == proposal.authorizationRevision &&
                authorizationRequestExpiresAt == proposal.authorizationRequest.expiresAt &&
                authorizationExpiresAt == proposal.authorizationExpiresAt,
        ) { "Agent approval request authorization evidence does not match." }
        require(
            authorizationAction == proposal.authorizationAction &&
                authorizationResourceType == proposal.authorizationResourceType &&
                authorizationResourceId == proposal.authorizationResourceId &&
                authorizationResourceRevision == proposal.authorizationResourceRevision &&
                authorizationPurpose == proposal.authorizationPurpose,
        ) { "Agent approval request authorization scope does not match." }
        require(atTime >= requestedAt && atTime < expiresAt) {
            "Agent approval request is not valid at the requested time."
        }
    }

    override fun toString(): String = "AgentApprovalRequest(toolId=${toolId.value})"

    companion object {
        @JvmStatic
        fun create(
            requestId: Identifier,
            proposal: AgentPolicyProposal,
            policyDecision: AgentPolicyDecision,
            operatorId: Identifier,
            operatorType: String,
            nonce: String,
            requestedAt: Long,
            expiresAt: Long,
        ): AgentApprovalRequest {
            require(policyDecision.outcome == AgentPolicyOutcome.REQUIRE_APPROVAL) {
                "Agent approval requests require a REQUIRE_APPROVAL policy decision."
            }
            policyDecision.requireValidFor(proposal, requestedAt)
            return AgentApprovalRequest(
                requestId,
                proposal.proposalId,
                policyDecision.decisionId,
                policyDecision.providerId,
                policyDecision.policyInputDigest,
                policyDecision.policyRevision,
                policyDecision.expiresAt,
                policyDecision.outcome,
                proposal.tenantId,
                proposal.principalId,
                proposal.principalType,
                proposal.runId,
                proposal.stepId,
                proposal.toolProviderId,
                proposal.toolId,
                proposal.descriptorDigest,
                proposal.schemaDigest,
                proposal.argumentsDigest,
                proposal.idempotencyKeyDigest,
                proposal.authorizationRequestId,
                proposal.executionContextId,
                proposal.authorizationProviderId,
                proposal.authorizationDecisionId,
                proposal.authorizationBindingDigest,
                proposal.authorizationRevision,
                proposal.authorizationRequest.expiresAt,
                proposal.authorizationExpiresAt,
                proposal.authorizationAction,
                proposal.authorizationResourceType,
                proposal.authorizationResourceId,
                proposal.authorizationResourceRevision,
                proposal.authorizationPurpose,
                operatorId,
                operatorType,
                nonce,
                requestedAt,
                expiresAt,
                null,
            )
        }

        /** Restores standalone approval-event evidence and verifies every stored field digest. */
        @JvmStatic
        fun restore(
            requestId: Identifier,
            proposalId: Identifier,
            policyDecisionId: Identifier,
            policyProviderId: ProviderId,
            policyInputDigest: String,
            policyRevision: String,
            policyExpiresAt: Long,
            policyOutcome: AgentPolicyOutcome,
            tenantId: Identifier,
            principalId: Identifier,
            principalType: String,
            runId: Identifier,
            stepId: Identifier,
            toolProviderId: ProviderId,
            toolId: ToolId,
            descriptorDigest: String,
            schemaDigest: String,
            argumentsDigest: String,
            idempotencyKeyDigest: String,
            authorizationRequestId: Identifier,
            executionContextId: Identifier,
            authorizationProviderId: ProviderId,
            authorizationDecisionId: Identifier,
            authorizationBindingDigest: String,
            authorizationRevision: String,
            authorizationRequestExpiresAt: Long,
            authorizationExpiresAt: Long,
            authorizationAction: String,
            authorizationResourceType: String,
            authorizationResourceId: Identifier,
            authorizationResourceRevision: String,
            authorizationPurpose: String,
            operatorId: Identifier,
            operatorType: String,
            nonce: String,
            requestedAt: Long,
            expiresAt: Long,
            evidenceDigest: String,
        ): AgentApprovalRequest = AgentApprovalRequest(
            requestId,
            proposalId,
            policyDecisionId,
            policyProviderId,
            policyInputDigest,
            policyRevision,
            policyExpiresAt,
            policyOutcome,
            tenantId,
            principalId,
            principalType,
            runId,
            stepId,
            toolProviderId,
            toolId,
            descriptorDigest,
            schemaDigest,
            argumentsDigest,
            idempotencyKeyDigest,
            authorizationRequestId,
            executionContextId,
            authorizationProviderId,
            authorizationDecisionId,
            authorizationBindingDigest,
            authorizationRevision,
            authorizationRequestExpiresAt,
            authorizationExpiresAt,
            authorizationAction,
            authorizationResourceType,
            authorizationResourceId,
            authorizationResourceRevision,
            authorizationPurpose,
            operatorId,
            operatorType,
            nonce,
            requestedAt,
            expiresAt,
            evidenceDigest,
        )
    }
}

enum class AgentApprovalOutcome {
    APPROVED,
    REJECTED,
}

/** Standalone approval evidence carrying every security-relevant binding from its request. */
class AgentApprovalDecision private constructor(
    decisionId: Identifier,
    request: AgentApprovalRequest,
    operatorId: Identifier,
    operatorType: String,
    val outcome: AgentApprovalOutcome,
    val decidedAt: Long,
    reasonCode: String?,
) {
    val decisionId: Identifier = requireOpaqueIdentifier(decisionId, "Agent approval decision identifier is invalid.")
    val requestId: Identifier = request.requestId
    val proposalId: Identifier = request.proposalId
    val policyDecisionId: Identifier = request.policyDecisionId
    val policyProviderId: ProviderId = request.policyProviderId
    val policyInputDigest: String = request.policyInputDigest
    val policyRevision: String = request.policyRevision
    val policyExpiresAt: Long = request.policyExpiresAt
    val tenantId: Identifier = request.tenantId
    val principalId: Identifier = request.principalId
    val principalType: String = request.principalType
    val runId: Identifier = request.runId
    val stepId: Identifier = request.stepId
    val toolProviderId: ProviderId = request.toolProviderId
    val toolId: ToolId = request.toolId
    val descriptorDigest: String = request.descriptorDigest
    val schemaDigest: String = request.schemaDigest
    val argumentsDigest: String = request.argumentsDigest
    val idempotencyKeyDigest: String = request.idempotencyKeyDigest
    val authorizationRequestId: Identifier = request.authorizationRequestId
    val executionContextId: Identifier = request.executionContextId
    val authorizationProviderId: ProviderId = request.authorizationProviderId
    val authorizationDecisionId: Identifier = request.authorizationDecisionId
    val authorizationBindingDigest: String = request.authorizationBindingDigest
    val authorizationRevision: String = request.authorizationRevision
    val authorizationRequestExpiresAt: Long = request.authorizationRequestExpiresAt
    val authorizationExpiresAt: Long = request.authorizationExpiresAt
    val authorizationAction: String = request.authorizationAction
    val authorizationResourceType: String = request.authorizationResourceType
    val authorizationResourceId: Identifier = request.authorizationResourceId
    val authorizationResourceRevision: String = request.authorizationResourceRevision
    val authorizationPurpose: String = request.authorizationPurpose
    val operatorId: Identifier = requireOpaqueIdentifier(operatorId, "Agent approval operator identifier is invalid.")
    val operatorType: String = requireAgentCode(operatorType, "Agent approval operator type is invalid.")
    val nonce: String = request.nonce
    val expiresAt: Long = request.expiresAt
    val reasonCode: String? = reasonCode?.let { requireAgentCode(it, "Agent approval reason code is invalid.") }

    init {
        require(this.operatorId == request.operatorId && this.operatorType == request.operatorType) {
            "Agent approval operator does not match the request."
        }
        require(decidedAt >= request.requestedAt && decidedAt < request.expiresAt) {
            "Agent approval decision time must fall within the request lifetime."
        }
        require(outcome != AgentApprovalOutcome.REJECTED || this.reasonCode != null) {
            "Rejected Agent approvals require a reason code."
        }
    }

    fun requireValidFor(request: AgentApprovalRequest, atTime: Long) {
        require(
            requestId == request.requestId &&
                proposalId == request.proposalId &&
                policyDecisionId == request.policyDecisionId &&
                policyProviderId == request.policyProviderId &&
                policyInputDigest == request.policyInputDigest &&
                policyRevision == request.policyRevision &&
                policyExpiresAt == request.policyExpiresAt,
        ) { "Agent approval decision policy binding does not match." }
        require(
            tenantId == request.tenantId &&
                principalId == request.principalId &&
                principalType == request.principalType &&
                runId == request.runId &&
                stepId == request.stepId,
        ) { "Agent approval decision context does not match." }
        require(
            toolProviderId == request.toolProviderId &&
                toolId == request.toolId &&
                descriptorDigest == request.descriptorDigest &&
                schemaDigest == request.schemaDigest &&
                argumentsDigest == request.argumentsDigest &&
                idempotencyKeyDigest == request.idempotencyKeyDigest,
        ) { "Agent approval decision tool binding does not match." }
        require(
            authorizationRequestId == request.authorizationRequestId &&
                executionContextId == request.executionContextId &&
                authorizationProviderId == request.authorizationProviderId &&
                authorizationDecisionId == request.authorizationDecisionId &&
                authorizationBindingDigest == request.authorizationBindingDigest &&
                authorizationRevision == request.authorizationRevision &&
                authorizationRequestExpiresAt == request.authorizationRequestExpiresAt &&
                authorizationExpiresAt == request.authorizationExpiresAt,
        ) { "Agent approval decision authorization evidence does not match." }
        require(
            authorizationAction == request.authorizationAction &&
                authorizationResourceType == request.authorizationResourceType &&
                authorizationResourceId == request.authorizationResourceId &&
                authorizationResourceRevision == request.authorizationResourceRevision &&
                authorizationPurpose == request.authorizationPurpose,
        ) { "Agent approval decision authorization scope does not match." }
        require(
            operatorId == request.operatorId &&
                operatorType == request.operatorType &&
                nonce == request.nonce &&
                expiresAt == request.expiresAt,
        ) {
            "Agent approval decision operator, nonce, or expiry does not match."
        }
        require(atTime >= decidedAt && atTime < expiresAt) {
            "Agent approval decision is not valid at the requested time."
        }
    }

    fun requireApprovedFor(
        request: AgentApprovalRequest,
        proposal: AgentPolicyProposal,
        policyDecision: AgentPolicyDecision,
        atTime: Long,
    ) {
        request.requireValidFor(proposal, policyDecision, atTime)
        requireValidFor(request, atTime)
        require(outcome == AgentApprovalOutcome.APPROVED) { "Agent approval decision is not approved." }
    }

    override fun toString(): String = "AgentApprovalDecision(outcome=$outcome)"

    companion object {
        @JvmStatic
        @JvmOverloads
        fun approve(
            decisionId: Identifier,
            request: AgentApprovalRequest,
            operatorId: Identifier,
            operatorType: String,
            decidedAt: Long,
            reasonCode: String? = null,
        ): AgentApprovalDecision = AgentApprovalDecision(
            decisionId,
            request,
            operatorId,
            operatorType,
            AgentApprovalOutcome.APPROVED,
            decidedAt,
            reasonCode,
        )

        @JvmStatic
        fun reject(
            decisionId: Identifier,
            request: AgentApprovalRequest,
            operatorId: Identifier,
            operatorType: String,
            decidedAt: Long,
            reasonCode: String,
        ): AgentApprovalDecision = AgentApprovalDecision(
            decisionId,
            request,
            operatorId,
            operatorType,
            AgentApprovalOutcome.REJECTED,
            decidedAt,
            reasonCode,
        )
    }
}
