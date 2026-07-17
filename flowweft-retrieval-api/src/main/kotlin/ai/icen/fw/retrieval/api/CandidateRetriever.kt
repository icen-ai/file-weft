package ai.icen.fw.retrieval.api

/** Immutable provider identity and exact pre-selection capability declaration. */
class CandidateRetrieverDescriptor private constructor(
    val providerTypeId: String,
    val providerInstanceId: String,
    val configurationDigest: String,
    val securityDomainDigest: String,
    val capabilityRevision: String,
    val tenantConstraintFieldId: String,
    val tenantConstraintCapabilityRevision: String,
    supportedModes: Collection<RetrievalMode>,
    supportedAccessProfiles: Collection<RetrievalAccessProfile>,
    val maximumCandidateLimit: Int,
    val maximumAuthorizedDocumentIds: Int,
    val sendsQueryOffHost: Boolean,
    val supportsCancellation: Boolean,
    val supportsCursorPagination: Boolean,
    val enforcesTenantAndAccessBeforeRanking: Boolean,
    val mandatoryFilterCapability: MandatoryFilterCapability?,
    val backendNativeAuthorityId: String?,
) {
    val supportedModes: Set<RetrievalMode> = immutableRetrievalSet(supportedModes)
    val supportedAccessProfiles: Set<RetrievalAccessProfile> = immutableRetrievalSet(supportedAccessProfiles)
    val digest: String

    init {
        requireRetrievalText(providerTypeId, RetrievalContractLimits.MAX_ID_CODE_POINTS, "Provider type is invalid.")
        requireRetrievalText(
            providerInstanceId,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Provider instance is invalid.",
        )
        requireDigest(configurationDigest, "Provider configuration digest is invalid.")
        requireDigest(securityDomainDigest, "Provider security-domain digest is invalid.")
        requireRetrievalText(
            capabilityRevision,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Provider capability revision is invalid.",
        )
        requireRetrievalText(
            tenantConstraintFieldId,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Provider tenant constraint field is invalid.",
        )
        requireRetrievalText(
            tenantConstraintCapabilityRevision,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Provider tenant constraint capability revision is invalid.",
        )
        require(this.supportedModes.isNotEmpty()) { "Candidate retriever must support at least one mode." }
        require(this.supportedAccessProfiles.isNotEmpty()) {
            "Candidate retriever must support at least one access profile."
        }
        require(maximumCandidateLimit in 1..RetrievalContractLimits.MAX_CANDIDATES) {
            "Provider candidate limit is invalid."
        }
        require(maximumAuthorizedDocumentIds in 1..RetrievalContractLimits.MAX_AUTHORIZED_DOCUMENTS) {
            "Provider authorized-document limit is invalid."
        }
        require(enforcesTenantAndAccessBeforeRanking) {
            "Candidate retrievers must enforce tenant and access filters before ranking."
        }
        if (RetrievalAccessProfile.MANDATORY_FILTER in this.supportedAccessProfiles) {
            requireNotNull(mandatoryFilterCapability) {
                "Mandatory-filter profile requires an exact filter capability."
            }
        } else {
            require(mandatoryFilterCapability == null) {
                "Mandatory-filter capability must not be declared when the profile is unsupported."
            }
        }
        if (RetrievalAccessProfile.BACKEND_NATIVE in this.supportedAccessProfiles) {
            requireRetrievalText(
                requireNotNull(backendNativeAuthorityId) {
                    "Backend-native profile requires an authority identifier."
                },
                RetrievalContractLimits.MAX_ID_CODE_POINTS,
                "Backend-native authority identifier is invalid.",
            )
        } else {
            require(backendNativeAuthorityId == null) {
                "Backend-native authority must not be declared when the profile is unsupported."
            }
        }
        digest = retrievalDigest {
            text("flowweft-candidate-retriever-descriptor-v2")
            text(providerTypeId)
            text(providerInstanceId)
            text(configurationDigest)
            text(securityDomainDigest)
            text(capabilityRevision)
            text(tenantConstraintFieldId)
            text(tenantConstraintCapabilityRevision)
            val modeIds = this@CandidateRetrieverDescriptor.supportedModes.map { it.id }.sorted()
            integer(modeIds.size)
            modeIds.forEach(::text)
            val profileIds = this@CandidateRetrieverDescriptor.supportedAccessProfiles.map { it.id }.sorted()
            integer(profileIds.size)
            profileIds.forEach(::text)
            integer(maximumCandidateLimit)
            integer(maximumAuthorizedDocumentIds)
            boolean(sendsQueryOffHost)
            boolean(supportsCancellation)
            boolean(supportsCursorPagination)
            boolean(enforcesTenantAndAccessBeforeRanking)
            writeMandatoryCapability(mandatoryFilterCapability)
            optionalText(backendNativeAuthorityId)
        }
    }

    fun requireSupports(
        plan: RetrievalAccessPlan,
        requestSpec: RetrievalRequestSpec,
        executionPolicy: RetrievalExecutionPolicy,
    ) {
        require(requestSpec.mode in supportedModes) { "Provider does not support the retrieval mode." }
        require(plan.profile in supportedAccessProfiles) { "Provider does not support the access profile." }
        require(requestSpec.candidateLimit <= maximumCandidateLimit) {
            "Retrieval candidate limit exceeds provider capability."
        }
        require(requestSpec.candidateLimit <= executionPolicy.maximumCandidateLimit) {
            "Retrieval candidate limit exceeds execution policy."
        }
        require(!sendsQueryOffHost || executionPolicy.queryOffHostAllowed) {
            "Retrieval query egress is forbidden by execution policy."
        }
        require(!executionPolicy.cancellationRequired || supportsCancellation) {
            "Execution policy requires provider cancellation support."
        }
        require(requestSpec.pageCursor == null || supportsCursorPagination) {
            "Provider does not support cursor pagination."
        }
        when (plan.profile) {
            RetrievalAccessProfile.AUTHORIZED_ID_SET -> require(
                plan.authorizedDocumentIds.size <= maximumAuthorizedDocumentIds,
            ) { "Authorized document set exceeds provider capability." }
            RetrievalAccessProfile.MANDATORY_FILTER -> checkNotNull(mandatoryFilterCapability)
                .requireSupports(checkNotNull(plan.mandatoryFilter))
            RetrievalAccessProfile.BACKEND_NATIVE -> {
                val scope = checkNotNull(plan.backendNativeScope)
                scope.requireProviderInstance(providerInstanceId)
                require(scope.authorityId == backendNativeAuthorityId) {
                    "Backend-native scope authority does not match the provider."
                }
                require(scope.securityDomainDigest == securityDomainDigest) {
                    "Backend-native scope security domain does not match the provider."
                }
            }
            else -> throw IllegalArgumentException("Unknown retrieval access profile cannot be executed.")
        }
    }

    override fun toString(): String = "CandidateRetrieverDescriptor(providerType=$providerTypeId)"

    class Builder private constructor(
        private val providerTypeId: String,
        private val providerInstanceId: String,
        private val configurationDigest: String,
        private val securityDomainDigest: String,
        private val capabilityRevision: String,
    ) {
        private val supportedModes = LinkedHashSet<RetrievalMode>()
        private val supportedAccessProfiles = LinkedHashSet<RetrievalAccessProfile>()
        private var maximumCandidateLimit = -1
        private var maximumAuthorizedDocumentIds = -1
        private var sendsQueryOffHost = false
        private var supportsCancellation = false
        private var supportsCursorPagination = false
        private var preselectionGuaranteed = false
        private var mandatoryFilterCapability: MandatoryFilterCapability? = null
        private var backendNativeAuthorityId: String? = null
        private var tenantConstraintFieldId: String? = null
        private var tenantConstraintCapabilityRevision: String? = null

        fun tenantConstraint(fieldId: String, capabilityRevision: String): Builder = apply {
            tenantConstraintFieldId = fieldId
            tenantConstraintCapabilityRevision = capabilityRevision
        }

        fun supportMode(mode: RetrievalMode): Builder = apply { supportedModes.add(mode) }

        fun supportAccessProfile(profile: RetrievalAccessProfile): Builder = apply {
            supportedAccessProfiles.add(profile)
        }

        fun limits(maximumCandidateLimit: Int, maximumAuthorizedDocumentIds: Int): Builder = apply {
            this.maximumCandidateLimit = maximumCandidateLimit
            this.maximumAuthorizedDocumentIds = maximumAuthorizedDocumentIds
        }

        fun queryEgress(sendsQueryOffHost: Boolean): Builder = apply {
            this.sendsQueryOffHost = sendsQueryOffHost
        }

        fun cancellation(supportsCancellation: Boolean): Builder = apply {
            this.supportsCancellation = supportsCancellation
        }

        fun cursorPagination(supportsCursorPagination: Boolean): Builder = apply {
            this.supportsCursorPagination = supportsCursorPagination
        }

        fun tenantAndAccessPreselectionGuaranteed(guaranteed: Boolean): Builder = apply {
            preselectionGuaranteed = guaranteed
        }

        fun mandatoryFilterCapability(capability: MandatoryFilterCapability): Builder = apply {
            mandatoryFilterCapability = capability
        }

        fun backendNativeAuthority(authorityId: String): Builder = apply {
            backendNativeAuthorityId = authorityId
        }

        fun build(): CandidateRetrieverDescriptor = CandidateRetrieverDescriptor(
            providerTypeId,
            providerInstanceId,
            configurationDigest,
            securityDomainDigest,
            capabilityRevision,
            requireNotNull(tenantConstraintFieldId) { "Provider tenant constraint must be configured." },
            requireNotNull(tenantConstraintCapabilityRevision) {
                "Provider tenant constraint capability revision must be configured."
            },
            supportedModes,
            supportedAccessProfiles,
            maximumCandidateLimit,
            maximumAuthorizedDocumentIds,
            sendsQueryOffHost,
            supportsCancellation,
            supportsCursorPagination,
            preselectionGuaranteed,
            mandatoryFilterCapability,
            backendNativeAuthorityId,
        )

        companion object {
            @JvmStatic
            fun create(
                providerTypeId: String,
                providerInstanceId: String,
                configurationDigest: String,
                securityDomainDigest: String,
                capabilityRevision: String,
            ): Builder = Builder(
                providerTypeId,
                providerInstanceId,
                configurationDigest,
                securityDomainDigest,
                capabilityRevision,
            )
        }
    }

    companion object {
        @JvmStatic
        fun builder(
            providerTypeId: String,
            providerInstanceId: String,
            configurationDigest: String,
            securityDomainDigest: String,
            capabilityRevision: String,
        ): Builder = Builder.create(
            providerTypeId,
            providerInstanceId,
            configurationDigest,
            securityDomainDigest,
            capabilityRevision,
        )
    }
}

private fun RetrievalDigestWriter.writeMandatoryCapability(capability: MandatoryFilterCapability?) {
    boolean(capability != null)
    capability ?: return
    text(capability.schemaId)
    text(capability.schemaRevision)
    val fields = capability.supportedFields.toSortedMap()
    integer(fields.size)
    fields.forEach { (field, operators) ->
        text(field)
        val names = operators.map { it.name }.sorted()
        integer(names.size)
        names.forEach(::text)
    }
    integer(capability.maxClauses)
    integer(capability.maxValuesPerClause)
    integer(capability.maxTotalValues)
    integer(capability.maxPayloadBytes)
}

/** Exact provider assertion for one attempt, descriptor, filter, generation and candidate list. */
class SecurityFilterReceipt private constructor(
    val attemptId: ai.icen.fw.core.id.Identifier,
    val requestId: ai.icen.fw.core.id.Identifier,
    val requestDigest: String,
    val authorizationRequestId: ai.icen.fw.core.id.Identifier,
    val authorizationRequestDigest: String,
    val accessDecisionId: ai.icen.fw.core.id.Identifier,
    val accessPlanDigest: String,
    val tenantId: ai.icen.fw.core.id.Identifier,
    val tenantConstraintDigest: String,
    val providerTypeId: String,
    val providerInstanceId: String,
    val providerConfigurationDigest: String,
    val providerDescriptorDigest: String,
    val accessProfile: RetrievalAccessProfile,
    val filterDigest: String,
    val scopeDigest: String,
    val policyRevision: String,
    private val candidateDigest: String,
    val indexGeneration: String,
    val filteredAtEpochMilli: Long,
    private val candidateCount: Int,
    val nextCursorDigest: String?,
    val partial: Boolean,
    val timedOut: Boolean,
) {
    val digest: String

    init {
        listOf(
            attemptId,
            requestId,
            authorizationRequestId,
            accessDecisionId,
            tenantId,
        ).forEach { identifier ->
            requireRetrievalIdentifier(identifier, "Security receipt identifier is invalid.")
        }
        listOf(
            requestDigest,
            authorizationRequestDigest,
            accessPlanDigest,
            tenantConstraintDigest,
            providerConfigurationDigest,
            providerDescriptorDigest,
            filterDigest,
            scopeDigest,
            candidateDigest,
        ).forEach { value -> requireDigest(value, "Security receipt digest binding is invalid.") }
        nextCursorDigest?.let { value ->
            requireDigest(value, "Security receipt continuation cursor digest is invalid.")
        }
        listOf(providerTypeId, providerInstanceId, policyRevision, indexGeneration).forEach { value ->
            requireRetrievalText(
                value,
                RetrievalContractLimits.MAX_ID_CODE_POINTS,
                "Security receipt text binding is invalid.",
            )
        }
        require(filteredAtEpochMilli >= 0L) { "Security receipt time must not be negative." }
        require(candidateCount in 0..RetrievalContractLimits.MAX_CANDIDATES) {
            "Security receipt candidate count is invalid."
        }
        require(!timedOut || partial) { "A timed-out security receipt must be marked partial." }
        digest = retrievalDigest {
            text("flowweft-retrieval-security-filter-receipt-v2")
            text(attemptId.value)
            text(requestId.value)
            text(requestDigest)
            text(authorizationRequestId.value)
            text(authorizationRequestDigest)
            text(accessDecisionId.value)
            text(accessPlanDigest)
            text(tenantId.value)
            text(tenantConstraintDigest)
            text(providerTypeId)
            text(providerInstanceId)
            text(providerConfigurationDigest)
            text(providerDescriptorDigest)
            text(accessProfile.id)
            text(filterDigest)
            text(scopeDigest)
            text(policyRevision)
            text(candidateDigest)
            text(indexGeneration)
            long(filteredAtEpochMilli)
            integer(candidateCount)
            optionalText(nextCursorDigest)
            boolean(partial)
            boolean(timedOut)
        }
    }

    internal fun requireExactCandidates(candidates: Collection<RetrievalCandidate>) {
        require(candidateCount == candidates.size && candidateDigest == candidateDigest(candidates)) {
            "Security receipt does not attest the exact candidate list."
        }
    }

    override fun toString(): String = "SecurityFilterReceipt(profile=${accessProfile.id})"

    companion object {
        @JvmSynthetic
        internal fun create(
            request: ExecutableRetrievalRequest,
            descriptor: CandidateRetrieverDescriptor,
            indexGeneration: String,
            filteredAtEpochMilli: Long,
            candidates: Collection<RetrievalCandidate>,
            nextCursor: RetrievalPageCursor?,
            partial: Boolean,
            timedOut: Boolean,
        ): SecurityFilterReceipt {
            val snapshot = immutableRetrievalList(
                candidates,
                RetrievalContractLimits.MAX_CANDIDATES,
                "Retrieval result contains too many candidates.",
            )
            require(snapshot.all { it.evidence.indexGeneration == indexGeneration }) {
                "Security receipt generation does not match every candidate."
            }
            return SecurityFilterReceipt(
                request.attemptId,
                request.requestId,
                request.digest,
                request.authorizationRequestId,
                request.authorizationRequestDigest,
                request.accessDecisionId,
                request.accessPlanDigest,
                request.tenantId,
                request.tenantConstraint.digest,
                descriptor.providerTypeId,
                descriptor.providerInstanceId,
                descriptor.configurationDigest,
                descriptor.digest,
                request.accessProfile,
                request.filterDigest,
                request.scopeDigest,
                request.policyRevision,
                candidateDigest(snapshot),
                indexGeneration,
                filteredAtEpochMilli,
                snapshot.size,
                nextCursor?.digest,
                partial,
                timedOut,
            )
        }

        internal fun candidateDigest(candidates: Collection<RetrievalCandidate>): String = retrievalDigest {
            text("flowweft-retrieval-candidates-v1")
            integer(candidates.size)
            candidates.forEach { it.writeDigest(this) }
        }
    }
}

/** Candidate-hiding provider result. Candidates become visible only after [verifyFor]. */
class RetrievalResultEnvelope private constructor(
    candidates: Collection<RetrievalCandidate>,
    val securityFilterReceipt: SecurityFilterReceipt,
    private val nextCursor: RetrievalPageCursor?,
    val partial: Boolean,
    val timedOut: Boolean,
) {
    private val candidates: List<RetrievalCandidate> = immutableRetrievalList(
        candidates,
        RetrievalContractLimits.MAX_CANDIDATES,
        "Retrieval result contains too many candidates.",
    )

    init {
        require(this.candidates.map { it.evidence.identityKey() }.toSet().size == this.candidates.size) {
            "Retrieval candidates must have unique evidence references."
        }
        require(this.candidates.mapIndexed { index, candidate -> candidate.providerRank == index + 1 }.all { it }) {
            "Retrieval candidate ranks must be unique, contiguous, and match result order."
        }
        securityFilterReceipt.requireExactCandidates(this.candidates)
        require(securityFilterReceipt.nextCursorDigest == nextCursor?.digest) {
            "Security receipt continuation cursor does not match the envelope."
        }
        require(securityFilterReceipt.partial == partial && securityFilterReceipt.timedOut == timedOut) {
            "Security receipt completion flags do not match the envelope."
        }
        require(!timedOut || partial) { "A timed-out result must be marked partial." }
    }

    fun verifyFor(
        request: ExecutableRetrievalRequest,
        descriptor: CandidateRetrieverDescriptor,
        nowEpochMilli: Long,
    ): PrefilteredCandidateBatch {
        require(nowEpochMilli >= 0L) { "Current time must not be negative." }
        require(nowEpochMilli < request.deadlineEpochMilli) { "Retrieval request deadline has expired." }
        require(nowEpochMilli < request.accessPlanExpiresAtEpochMilli) { "Retrieval access plan has expired." }
        require(request.providerDescriptorDigest == descriptor.digest) {
            "Executable request belongs to another provider descriptor."
        }
        val receipt = securityFilterReceipt
        require(receipt.attemptId == request.attemptId) { "Security receipt belongs to another attempt." }
        require(receipt.requestId == request.requestId && receipt.requestDigest == request.digest) {
            "Security receipt belongs to another executable request."
        }
        require(
            receipt.authorizationRequestId == request.authorizationRequestId &&
                receipt.authorizationRequestDigest == request.authorizationRequestDigest,
        ) { "Security receipt belongs to another authorization request." }
        require(
            receipt.accessDecisionId == request.accessDecisionId &&
                receipt.accessPlanDigest == request.accessPlanDigest,
        ) { "Security receipt belongs to another access decision." }
        require(receipt.tenantId == request.tenantId) { "Security receipt belongs to another tenant." }
        require(receipt.tenantConstraintDigest == request.tenantConstraint.digest) {
            "Security receipt does not bind the mandatory tenant constraint."
        }
        require(
            receipt.providerTypeId == descriptor.providerTypeId &&
                receipt.providerInstanceId == descriptor.providerInstanceId &&
                receipt.providerConfigurationDigest == descriptor.configurationDigest &&
                receipt.providerDescriptorDigest == descriptor.digest,
        ) { "Security receipt belongs to another provider identity or configuration." }
        require(
            receipt.accessProfile == request.accessProfile &&
                receipt.filterDigest == request.filterDigest &&
                receipt.scopeDigest == request.scopeDigest &&
                receipt.policyRevision == request.policyRevision,
        ) { "Security receipt does not match the access plan." }
        request.pageCursor?.let { cursor ->
            require(receipt.indexGeneration == cursor.indexGeneration) {
                "Retrieval cursor cannot cross an index-generation switch."
            }
        }
        require(receipt.filteredAtEpochMilli in request.preparedAtEpochMilli..nowEpochMilli) {
            "Security receipt time is outside the trusted request window."
        }
        require(candidates.size <= request.candidateLimit && candidates.size <= descriptor.maximumCandidateLimit) {
            "Retrieval result exceeds the authorized candidate limit."
        }
        require(candidates.all { it.evidence.tenantId == request.tenantId }) {
            "Retrieval result contains evidence from another tenant."
        }
        require(candidates.all { it.sourceMode == request.mode && it.sourceMode in descriptor.supportedModes }) {
            "Retrieval result contains an unexpected retrieval mode."
        }
        if (request.accessProfile == RetrievalAccessProfile.AUTHORIZED_ID_SET) {
            require(candidates.all { it.evidence.documentId in request.authorizedDocumentIds }) {
                "Retrieval result contains a document outside the authorized identifier set."
            }
        }
        return PrefilteredCandidateBatch.verified(
            request,
            candidates,
            receipt,
            nextCursor,
            partial,
            timedOut,
            nowEpochMilli,
        )
    }

    companion object {
        /** Provider adapter factory. Receipt creation and candidate snapshot are atomic. */
        @JvmStatic
        fun create(
            request: ExecutableRetrievalRequest,
            descriptor: CandidateRetrieverDescriptor,
            indexGeneration: String,
            filteredAtEpochMilli: Long,
            candidates: Collection<RetrievalCandidate>,
            partial: Boolean,
            timedOut: Boolean,
        ): RetrievalResultEnvelope = create(
            request,
            descriptor,
            indexGeneration,
            filteredAtEpochMilli,
            candidates,
            null,
            partial,
            timedOut,
        )

        /** Provider adapter factory with an optional bounded opaque continuation token. */
        @JvmStatic
        fun create(
            request: ExecutableRetrievalRequest,
            descriptor: CandidateRetrieverDescriptor,
            indexGeneration: String,
            filteredAtEpochMilli: Long,
            candidates: Collection<RetrievalCandidate>,
            nextCursorToken: String?,
            partial: Boolean,
            timedOut: Boolean,
        ): RetrievalResultEnvelope {
            require(request.providerDescriptorDigest == descriptor.digest) {
                "Executable request belongs to another provider descriptor."
            }
            val snapshot = immutableRetrievalList(
                candidates,
                RetrievalContractLimits.MAX_CANDIDATES,
                "Retrieval result contains too many candidates.",
            )
            val cursor = nextCursorToken?.let { token ->
                RetrievalPageCursor.next(request, descriptor, indexGeneration, token, filteredAtEpochMilli)
            }
            return RetrievalResultEnvelope(
                snapshot,
                SecurityFilterReceipt.create(
                    request,
                    descriptor,
                    indexGeneration,
                    filteredAtEpochMilli,
                    snapshot,
                    cursor,
                    partial,
                    timedOut,
                ),
                cursor,
                partial,
                timedOut,
            )
        }
    }
}

/** Candidate-bearing type obtainable only after exact pre-selection receipt verification. */
class PrefilteredCandidateBatch private constructor(
    val requestDigest: String,
    val providerDescriptorDigest: String,
    val sourceDeadlineEpochMilli: Long,
    val sourceAccessPlanExpiresAtEpochMilli: Long,
    candidates: Collection<RetrievalCandidate>,
    val securityFilterReceipt: SecurityFilterReceipt,
    val nextCursor: RetrievalPageCursor?,
    val partial: Boolean,
    val timedOut: Boolean,
    val verifiedAtEpochMilli: Long,
) {
    val candidates: List<RetrievalCandidate> = immutableRetrievalList(candidates)

    companion object {
        @JvmSynthetic
        internal fun verified(
            request: ExecutableRetrievalRequest,
            candidates: Collection<RetrievalCandidate>,
            receipt: SecurityFilterReceipt,
            nextCursor: RetrievalPageCursor?,
            partial: Boolean,
            timedOut: Boolean,
            verifiedAtEpochMilli: Long,
        ): PrefilteredCandidateBatch = PrefilteredCandidateBatch(
            request.digest,
            request.providerDescriptorDigest,
            request.deadlineEpochMilli,
            request.accessPlanExpiresAtEpochMilli,
            candidates,
            receipt,
            nextCursor,
            partial,
            timedOut,
            verifiedAtEpochMilli,
        )
    }
}

/** Provider boundary accepts only a request created by the execution gate. */
interface CandidateRetriever {
    fun descriptor(): CandidateRetrieverDescriptor
    fun start(request: ExecutableRetrievalRequest): RetrievalCall<RetrievalResultEnvelope>
}
