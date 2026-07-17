package ai.icen.fw.retrieval.api

import ai.icen.fw.core.id.Identifier

/** Extensible retrieval-mode identifier. Equality is exact and case-sensitive. */
class RetrievalMode private constructor(val id: String) {
    init {
        requireRetrievalText(id, RetrievalContractLimits.MAX_ID_CODE_POINTS, "Retrieval mode is invalid.")
        requireStableRetrievalCode(id, "Retrieval mode must be a stable lower-case ASCII identifier.")
    }

    override fun equals(other: Any?): Boolean = other is RetrievalMode && id == other.id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id

    companion object {
        @JvmField val EXACT_FILENAME = RetrievalMode("filename.exact")
        @JvmField val PREFIX_FILENAME = RetrievalMode("filename.prefix")
        @JvmField val CONTAINS_FILENAME = RetrievalMode("filename.contains")
        @JvmField val FULL_TEXT = RetrievalMode("content.full-text")
        @JvmField val VECTOR = RetrievalMode("content.vector")
        @JvmField val HYBRID = RetrievalMode("content.hybrid")

        @JvmStatic
        fun of(id: String): RetrievalMode = RetrievalMode(id)
    }
}

/** Provider-specific representation of the mandatory trusted tenant pre-selection. */
class RetrievalTenantConstraint private constructor(
    val tenantId: Identifier,
    val providerFieldId: String,
    val capabilityRevision: String,
    val authorizationRequestId: Identifier,
    val authorizationRequestDigest: String,
    val providerInstanceId: String,
    val providerDescriptorDigest: String,
) {
    val tenantValue: String = tenantId.value
    val digest: String

    init {
        requireRetrievalIdentifier(tenantId, "Tenant constraint identifier is invalid.")
        requireRetrievalIdentifier(authorizationRequestId, "Tenant constraint request identifier is invalid.")
        requireRetrievalText(
            providerFieldId,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Tenant constraint provider field is invalid.",
        )
        requireRetrievalText(
            capabilityRevision,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Tenant constraint capability revision is invalid.",
        )
        requireRetrievalText(
            providerInstanceId,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Tenant constraint provider instance is invalid.",
        )
        requireDigest(authorizationRequestDigest, "Tenant constraint request digest is invalid.")
        requireDigest(providerDescriptorDigest, "Tenant constraint provider descriptor digest is invalid.")
        digest = retrievalDigest {
            text("flowweft-retrieval-tenant-constraint-v1")
            text(tenantId.value)
            text(providerFieldId)
            text(capabilityRevision)
            text(authorizationRequestId.value)
            text(authorizationRequestDigest)
            text(providerInstanceId)
            text(providerDescriptorDigest)
        }
    }

    override fun toString(): String = "RetrievalTenantConstraint(field=$providerFieldId)"

    companion object {
        @JvmSynthetic
        internal fun create(
            request: RetrievalAuthorizationRequest,
            descriptor: CandidateRetrieverDescriptor,
        ): RetrievalTenantConstraint = RetrievalTenantConstraint(
            request.tenantId,
            descriptor.tenantConstraintFieldId,
            descriptor.tenantConstraintCapabilityRevision,
            request.id,
            request.digest,
            descriptor.providerInstanceId,
            descriptor.digest,
        )
    }
}

/** Caller intent before authorization and provider selection. It contains no access plan. */
class RetrievalRequestSpec private constructor(
    val id: Identifier,
    val mode: RetrievalMode,
    val query: String,
    val candidateLimit: Int,
    val deadlineEpochMilli: Long,
    val pageCursor: RetrievalPageCursor?,
) {
    val digest: String

    init {
        requireRetrievalIdentifier(id, "Retrieval request identifier is invalid.")
        requireRetrievalText(query, RetrievalContractLimits.MAX_QUERY_CODE_POINTS, "Retrieval query is invalid.")
        require(candidateLimit in 1..RetrievalContractLimits.MAX_CANDIDATES) {
            "Retrieval candidate limit is outside the supported range."
        }
        require(deadlineEpochMilli > 0L) { "Retrieval deadline must be positive." }
        pageCursor?.requireRequestShape(mode, query)
        digest = retrievalDigest {
            text("flowweft-retrieval-request-spec-v2")
            text(id.value)
            text(mode.id)
            text(query)
            integer(candidateLimit)
            long(deadlineEpochMilli)
            optionalText(pageCursor?.digest)
        }
    }

    override fun toString(): String = "RetrievalRequestSpec(mode=${mode.id})"

    companion object {
        @JvmStatic
        fun create(
            id: Identifier,
            mode: RetrievalMode,
            query: String,
            candidateLimit: Int,
            deadlineEpochMilli: Long,
        ): RetrievalRequestSpec = RetrievalRequestSpec(id, mode, query, candidateLimit, deadlineEpochMilli, null)

        @JvmStatic
        fun create(
            id: Identifier,
            mode: RetrievalMode,
            query: String,
            candidateLimit: Int,
            deadlineEpochMilli: Long,
            pageCursor: RetrievalPageCursor,
        ): RetrievalRequestSpec = RetrievalRequestSpec(
            id,
            mode,
            query,
            candidateLimit,
            deadlineEpochMilli,
            pageCursor,
        )
    }
}

/** Runtime egress, cancellation, size and duration policy applied before any provider call. */
class RetrievalExecutionPolicy private constructor(
    val queryOffHostAllowed: Boolean,
    val cancellationRequired: Boolean,
    val maximumCandidateLimit: Int,
    val maximumDurationMillis: Long,
) {
    val digest: String

    init {
        require(maximumCandidateLimit in 1..RetrievalContractLimits.MAX_CANDIDATES) {
            "Retrieval execution policy candidate limit is invalid."
        }
        require(maximumDurationMillis in 1..MAXIMUM_POLICY_DURATION_MILLIS) {
            "Retrieval execution policy duration is invalid."
        }
        digest = retrievalDigest {
            text("flowweft-retrieval-execution-policy-v1")
            boolean(queryOffHostAllowed)
            boolean(cancellationRequired)
            integer(maximumCandidateLimit)
            long(maximumDurationMillis)
        }
    }

    companion object {
        const val MAXIMUM_POLICY_DURATION_MILLIS: Long = 300_000L

        @JvmStatic
        fun create(
            queryOffHostAllowed: Boolean,
            cancellationRequired: Boolean,
            maximumCandidateLimit: Int,
            maximumDurationMillis: Long,
        ): RetrievalExecutionPolicy = RetrievalExecutionPolicy(
            queryOffHostAllowed,
            cancellationRequired,
            maximumCandidateLimit,
            maximumDurationMillis,
        )
    }
}

/**
 * Provider-minimal request produced only by [RetrievalExecutionGate]. It deliberately excludes
 * principal attributes, action and purpose. Every security and provider binding contributes to
 * [digest], so a receipt cannot be replayed across attempts or provider configurations.
 */
class ExecutableRetrievalRequest private constructor(
    val attemptId: Identifier,
    val requestId: Identifier,
    val requestSpecDigest: String,
    val authorizationRequestId: Identifier,
    val authorizationRequestDigest: String,
    val accessDecisionId: Identifier,
    val accessPlanDigest: String,
    val tenantId: Identifier,
    val tenantConstraint: RetrievalTenantConstraint,
    val mode: RetrievalMode,
    val query: String,
    val candidateLimit: Int,
    val deadlineEpochMilli: Long,
    val accessProfile: RetrievalAccessProfile,
    val authorizationAuthorityId: String,
    val policyRevision: String,
    val accessPlanIssuedAtEpochMilli: Long,
    val accessPlanExpiresAtEpochMilli: Long,
    val preparedAtEpochMilli: Long,
    val filterDigest: String,
    val scopeDigest: String,
    authorizedDocumentIds: Collection<Identifier>,
    val mandatoryFilter: MandatoryFilter?,
    val backendNativeScope: BackendNativeScope?,
    val providerTypeId: String,
    val providerInstanceId: String,
    val providerConfigurationDigest: String,
    val providerDescriptorDigest: String,
    val executionPolicyDigest: String,
    val pageCursor: RetrievalPageCursor?,
) {
    val authorizedDocumentIds: Set<Identifier> = immutableRetrievalSet(authorizedDocumentIds)
    val digest: String

    init {
        requireRetrievalIdentifier(attemptId, "Retrieval attempt identifier is invalid.")
        requireRetrievalIdentifier(requestId, "Retrieval request identifier is invalid.")
        requireDigest(requestSpecDigest, "Retrieval request-spec digest is invalid.")
        requireRetrievalIdentifier(authorizationRequestId, "Retrieval authorization request identifier is invalid.")
        requireDigest(authorizationRequestDigest, "Retrieval authorization request digest is invalid.")
        requireRetrievalIdentifier(accessDecisionId, "Retrieval access decision identifier is invalid.")
        requireDigest(accessPlanDigest, "Retrieval access-plan digest is invalid.")
        requireRetrievalIdentifier(tenantId, "Retrieval tenant identifier is invalid.")
        require(
            tenantConstraint.tenantId == tenantId &&
                tenantConstraint.authorizationRequestId == authorizationRequestId &&
                tenantConstraint.authorizationRequestDigest == authorizationRequestDigest,
        ) { "Retrieval tenant constraint belongs to another tenant or authorization request." }
        requireRetrievalText(query, RetrievalContractLimits.MAX_QUERY_CODE_POINTS, "Retrieval query is invalid.")
        require(candidateLimit in 1..RetrievalContractLimits.MAX_CANDIDATES) {
            "Retrieval candidate limit is outside the supported range."
        }
        require(deadlineEpochMilli > accessPlanIssuedAtEpochMilli) {
            "Retrieval deadline must be after access-plan issuance."
        }
        require(deadlineEpochMilli <= accessPlanExpiresAtEpochMilli) {
            "Retrieval deadline must not outlive its access plan."
        }
        require(preparedAtEpochMilli >= accessPlanIssuedAtEpochMilli &&
            preparedAtEpochMilli < deadlineEpochMilli &&
            preparedAtEpochMilli < accessPlanExpiresAtEpochMilli) {
            "Retrieval preparation time is outside the request validity window."
        }
        requireDigest(filterDigest, "Retrieval filter digest is invalid.")
        requireDigest(scopeDigest, "Retrieval scope digest is invalid.")
        requireRetrievalText(
            authorizationAuthorityId,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Retrieval authorization authority identifier is invalid.",
        )
        requireRetrievalText(
            policyRevision,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Retrieval policy revision is invalid.",
        )
        requireRetrievalText(providerTypeId, RetrievalContractLimits.MAX_ID_CODE_POINTS, "Provider type is invalid.")
        requireRetrievalText(
            providerInstanceId,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Provider instance is invalid.",
        )
        requireDigest(providerConfigurationDigest, "Provider configuration digest is invalid.")
        requireDigest(providerDescriptorDigest, "Provider descriptor digest is invalid.")
        require(
            tenantConstraint.providerInstanceId == providerInstanceId &&
                tenantConstraint.providerDescriptorDigest == providerDescriptorDigest,
        ) { "Retrieval tenant constraint belongs to another provider descriptor." }
        requireDigest(executionPolicyDigest, "Retrieval execution-policy digest is invalid.")
        pageCursor?.let { cursor ->
            require(cursor.tenantId == tenantId && cursor.mode == mode) {
                "Retrieval page cursor belongs to another tenant or retrieval mode."
            }
            require(cursor.providerDescriptorDigest == providerDescriptorDigest) {
                "Retrieval page cursor belongs to another provider descriptor."
            }
        }
        when (accessProfile) {
            RetrievalAccessProfile.AUTHORIZED_ID_SET -> require(
                this.authorizedDocumentIds.isNotEmpty() && mandatoryFilter == null && backendNativeScope == null,
            ) { "Executable authorized-ID request has an invalid filter payload." }
            RetrievalAccessProfile.MANDATORY_FILTER -> require(
                this.authorizedDocumentIds.isEmpty() && mandatoryFilter != null && backendNativeScope == null,
            ) { "Executable mandatory-filter request has an invalid filter payload." }
            RetrievalAccessProfile.BACKEND_NATIVE -> require(
                this.authorizedDocumentIds.isEmpty() && mandatoryFilter == null && backendNativeScope != null,
            ) { "Executable backend-native request has an invalid filter payload." }
            else -> throw IllegalArgumentException("Unknown retrieval access profile cannot be executed.")
        }
        digest = retrievalDigest {
            text("flowweft-executable-retrieval-request-v2")
            text(attemptId.value)
            text(requestId.value)
            text(requestSpecDigest)
            text(authorizationRequestId.value)
            text(authorizationRequestDigest)
            text(accessDecisionId.value)
            text(accessPlanDigest)
            text(tenantId.value)
            text(tenantConstraint.digest)
            text(mode.id)
            text(query)
            integer(candidateLimit)
            long(deadlineEpochMilli)
            text(accessProfile.id)
            text(authorizationAuthorityId)
            text(policyRevision)
            long(accessPlanIssuedAtEpochMilli)
            long(accessPlanExpiresAtEpochMilli)
            long(preparedAtEpochMilli)
            text(filterDigest)
            text(scopeDigest)
            text(providerTypeId)
            text(providerInstanceId)
            text(providerConfigurationDigest)
            text(providerDescriptorDigest)
            text(executionPolicyDigest)
            optionalText(pageCursor?.digest)
        }
    }

    override fun toString(): String =
        "ExecutableRetrievalRequest(mode=${mode.id}, providerType=$providerTypeId)"

    companion object {
        @JvmSynthetic
        internal fun create(
            attemptId: Identifier,
            authorizationRequest: RetrievalAuthorizationRequest,
            plan: RetrievalAccessPlan,
            requestSpec: RetrievalRequestSpec,
            descriptor: CandidateRetrieverDescriptor,
            executionPolicy: RetrievalExecutionPolicy,
            preparedAtEpochMilli: Long,
        ): ExecutableRetrievalRequest = ExecutableRetrievalRequest(
            attemptId,
            requestSpec.id,
            requestSpec.digest,
            authorizationRequest.id,
            authorizationRequest.digest,
            plan.decisionId,
            plan.digest,
            plan.tenantId,
            RetrievalTenantConstraint.create(authorizationRequest, descriptor),
            requestSpec.mode,
            requestSpec.query,
            requestSpec.candidateLimit,
            requestSpec.deadlineEpochMilli,
            plan.profile,
            plan.authorizationAuthorityId,
            plan.policyRevision,
            plan.issuedAtEpochMilli,
            plan.expiresAtEpochMilli,
            preparedAtEpochMilli,
            plan.filterDigest,
            plan.scopeDigest,
            plan.authorizedDocumentIds,
            plan.mandatoryFilter,
            plan.backendNativeScope,
            descriptor.providerTypeId,
            descriptor.providerInstanceId,
            descriptor.configurationDigest,
            descriptor.digest,
            executionPolicy.digest,
            requestSpec.pageCursor,
        )
    }
}

/** Result of preflight. A denied result has no executable request and therefore no call path. */
class RetrievalPreparation private constructor(
    val authorizationRequestId: Identifier,
    val authorizationRequestDigest: String,
    val requestId: Identifier,
    val requestSpecDigest: String,
    val authorizationAuthorityId: String,
    val policyRevision: String,
    val denialCode: RetrievalDenialCode?,
    private val executableRequest: ExecutableRetrievalRequest?,
) {
    val providerCallAllowed: Boolean = executableRequest != null

    init {
        require((executableRequest == null) == (denialCode != null)) {
            "Retrieval preparation must contain exactly one executable or denied outcome."
        }
    }

    fun requireExecutable(): ExecutableRetrievalRequest =
        executableRequest ?: throw IllegalStateException("Retrieval preparation denied provider execution.")

    companion object {
        @JvmSynthetic
        internal fun denied(
            authorizationRequest: RetrievalAuthorizationRequest,
            requestSpec: RetrievalRequestSpec,
            result: RetrievalPlanResult,
        ): RetrievalPreparation = RetrievalPreparation(
            authorizationRequest.id,
            authorizationRequest.digest,
            requestSpec.id,
            requestSpec.digest,
            result.authorizationAuthorityId,
            result.policyRevision,
            checkNotNull(result.denialCode),
            null,
        )

        @JvmSynthetic
        internal fun executable(request: ExecutableRetrievalRequest): RetrievalPreparation = RetrievalPreparation(
            request.authorizationRequestId,
            request.authorizationRequestDigest,
            request.requestId,
            request.requestSpecDigest,
            request.authorizationAuthorityId,
            request.policyRevision,
            null,
            request,
        )
    }
}

/** The only factory for provider-executable requests. Every check happens before [CandidateRetriever.start]. */
class RetrievalExecutionGate private constructor() {
    companion object {
        @JvmStatic
        fun prepare(
            attemptId: Identifier,
            authorizationRequest: RetrievalAuthorizationRequest,
            planResult: RetrievalPlanResult,
            requestSpec: RetrievalRequestSpec,
            descriptor: CandidateRetrieverDescriptor,
            executionPolicy: RetrievalExecutionPolicy,
            nowEpochMilli: Long,
        ): RetrievalPreparation {
            require(nowEpochMilli >= 0L) { "Current time must not be negative." }
            requireRetrievalIdentifier(attemptId, "Retrieval attempt identifier is invalid.")
            planResult.requireValidFor(authorizationRequest)
            require(planResult.decidedAtEpochMilli <= nowEpochMilli) {
                "Retrieval authorization decision was made in the future."
            }
            if (!planResult.allowed) {
                return RetrievalPreparation.denied(authorizationRequest, requestSpec, planResult)
            }

            val plan = planResult.requireAllowed()
            plan.requireValidFor(authorizationRequest, nowEpochMilli)
            require(requestSpec.deadlineEpochMilli > nowEpochMilli) { "Retrieval deadline has expired." }
            require(requestSpec.deadlineEpochMilli <= plan.expiresAtEpochMilli) {
                "Retrieval request outlives its authorization plan."
            }
            require(requestSpec.deadlineEpochMilli - nowEpochMilli <= executionPolicy.maximumDurationMillis) {
                "Retrieval request exceeds the execution-policy duration."
            }
            descriptor.requireSupports(plan, requestSpec, executionPolicy)
            requestSpec.pageCursor?.requireValidFor(requestSpec, plan, descriptor, nowEpochMilli)
            return RetrievalPreparation.executable(
                ExecutableRetrievalRequest.create(
                    attemptId,
                    authorizationRequest,
                    plan,
                    requestSpec,
                    descriptor,
                    executionPolicy,
                    nowEpochMilli,
                ),
            )
        }
    }
}
