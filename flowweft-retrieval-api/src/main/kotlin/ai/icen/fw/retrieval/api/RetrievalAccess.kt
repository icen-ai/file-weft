package ai.icen.fw.retrieval.api

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationSubject

/** Stable, extensible identifier for mandatory pre-selection semantics. */
class RetrievalAccessProfile private constructor(val id: String) {
    init {
        requireRetrievalText(
            id,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Retrieval access profile identifier is invalid.",
        )
        requireStableRetrievalCode(id, "Retrieval access profile must be a stable lower-case ASCII identifier.")
    }

    override fun equals(other: Any?): Boolean = other is RetrievalAccessProfile && id == other.id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id

    companion object {
        @JvmField val AUTHORIZED_ID_SET = RetrievalAccessProfile("authorized-id-set")
        @JvmField val MANDATORY_FILTER = RetrievalAccessProfile("mandatory-filter")
        @JvmField val BACKEND_NATIVE = RetrievalAccessProfile("backend-native")

        @JvmStatic
        fun of(id: String): RetrievalAccessProfile = RetrievalAccessProfile(id)
    }
}

/** Minimal trusted identity. Provider requests never receive subject attributes or purpose. */
class RetrievalPrincipal private constructor(
    val id: Identifier,
    val type: String,
) {
    init {
        requireRetrievalIdentifier(id, "Retrieval principal identifier is invalid.")
        requireRetrievalText(
            type,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Retrieval principal type is invalid.",
        )
    }

    override fun equals(other: Any?): Boolean =
        other is RetrievalPrincipal && id == other.id && type == other.type

    override fun hashCode(): Int = 31 * id.hashCode() + type.hashCode()
    override fun toString(): String = "RetrievalPrincipal(type=$type)"

    companion object {
        @JvmStatic
        fun create(id: Identifier, type: String): RetrievalPrincipal = RetrievalPrincipal(id, type)

        @JvmStatic
        fun from(subject: AuthorizationSubject): RetrievalPrincipal =
            RetrievalPrincipal(subject.id, subject.type)
    }
}

/** Trusted subject snapshot used only while the host compiles and later rechecks authorization. */
class RetrievalAuthorizationSubject private constructor(
    val principal: RetrievalPrincipal,
    attributes: Map<String, String>,
) {
    val attributes: Map<String, String> = immutableStringMap(
        attributes,
        RetrievalContractLimits.MAX_CLAIMS,
        "Retrieval authorization subject has too many attributes.",
    )
    val digest: String

    init {
        require(this.attributes.size <= RetrievalContractLimits.MAX_CLAIMS) {
            "Retrieval authorization subject has too many attributes."
        }
        this.attributes.forEach { (name, value) ->
            requireRetrievalText(
                name,
                RetrievalContractLimits.MAX_ID_CODE_POINTS,
                "Retrieval authorization subject attribute name is invalid.",
            )
            requireRetrievalText(
                value,
                RetrievalContractLimits.MAX_PURPOSE_CODE_POINTS,
                "Retrieval authorization subject attribute value is invalid.",
            )
        }
        digest = retrievalDigest {
            text("flowweft-retrieval-authorization-subject-v1")
            text(principal.id.value)
            text(principal.type)
            integer(this@RetrievalAuthorizationSubject.attributes.size)
            this@RetrievalAuthorizationSubject.attributes.toSortedMap().forEach { (name, value) ->
                text(name)
                text(value)
            }
        }
    }

    override fun toString(): String = "RetrievalAuthorizationSubject(principalType=${principal.type})"

    companion object {
        @JvmStatic
        fun create(principal: RetrievalPrincipal, attributes: Map<String, String>): RetrievalAuthorizationSubject =
            RetrievalAuthorizationSubject(principal, attributes)

        @JvmStatic
        fun from(subject: AuthorizationSubject): RetrievalAuthorizationSubject =
            RetrievalAuthorizationSubject(RetrievalPrincipal.from(subject), subject.attributes)
    }
}

/** Trusted authorization question. Purpose and attributes are never copied into provider input. */
class RetrievalAuthorizationRequest private constructor(
    val id: Identifier,
    val tenantId: Identifier,
    val subject: RetrievalAuthorizationSubject,
    val action: String,
    val purposeCode: String,
    val requestedAtEpochMilli: Long,
) {
    val digest: String

    init {
        requireRetrievalIdentifier(id, "Retrieval authorization request identifier is invalid.")
        requireRetrievalIdentifier(tenantId, "Retrieval tenant identifier is invalid.")
        requireRetrievalText(action, RetrievalContractLimits.MAX_ID_CODE_POINTS, "Retrieval action is invalid.")
        requireRetrievalText(
            purposeCode,
            RetrievalContractLimits.MAX_PURPOSE_CODE_POINTS,
            "Retrieval purpose code is invalid.",
        )
        require(requestedAtEpochMilli >= 0L) { "Retrieval authorization request time must not be negative." }
        digest = retrievalDigest {
            text("flowweft-retrieval-authorization-request-v1")
            text(id.value)
            text(tenantId.value)
            text(subject.digest)
            text(action)
            text(purposeCode)
            long(requestedAtEpochMilli)
        }
    }

    override fun toString(): String = "RetrievalAuthorizationRequest(action=$action)"

    companion object {
        @JvmStatic
        fun create(
            id: Identifier,
            tenantId: Identifier,
            subject: RetrievalAuthorizationSubject,
            action: String,
            purposeCode: String,
            requestedAtEpochMilli: Long,
        ): RetrievalAuthorizationRequest = RetrievalAuthorizationRequest(
            id,
            tenantId,
            subject,
            action,
            purposeCode,
            requestedAtEpochMilli,
        )
    }
}

/**
 * Short-lived authorization output bound to one complete trusted request and one exact filter.
 * Empty authorized-ID decisions are represented by [RetrievalPlanResult.deny], never by a plan.
 */
class RetrievalAccessPlan private constructor(
    val decisionId: Identifier,
    val authorizationRequestId: Identifier,
    val authorizationRequestDigest: String,
    val tenantId: Identifier,
    val principal: RetrievalPrincipal,
    val subjectDigest: String,
    val action: String,
    val purposeCode: String,
    val profile: RetrievalAccessProfile,
    val authorizationAuthorityId: String,
    val policyRevision: String,
    val issuedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
    authorizedDocumentIds: Collection<Identifier>,
    val mandatoryFilter: MandatoryFilter?,
    val backendNativeScope: BackendNativeScope?,
) {
    val authorizedDocumentIds: Set<Identifier> = immutableRetrievalSet(
        authorizedDocumentIds,
        RetrievalContractLimits.MAX_AUTHORIZED_DOCUMENTS,
        "Retrieval access plan contains too many document identifiers.",
    )
    val filterDigest: String
    val scopeDigest: String
    val digest: String

    init {
        requireRetrievalIdentifier(decisionId, "Retrieval authorization decision identifier is invalid.")
        requireRetrievalIdentifier(authorizationRequestId, "Retrieval authorization request identifier is invalid.")
        requireDigest(authorizationRequestDigest, "Retrieval authorization request digest is invalid.")
        requireRetrievalIdentifier(tenantId, "Retrieval tenant identifier is invalid.")
        requireDigest(subjectDigest, "Retrieval subject digest is invalid.")
        requireRetrievalText(action, RetrievalContractLimits.MAX_ID_CODE_POINTS, "Retrieval action is invalid.")
        requireRetrievalText(
            purposeCode,
            RetrievalContractLimits.MAX_PURPOSE_CODE_POINTS,
            "Retrieval purpose code is invalid.",
        )
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
        require(issuedAtEpochMilli >= 0L) { "Retrieval access plan issue time must not be negative." }
        require(expiresAtEpochMilli > issuedAtEpochMilli) {
            "Retrieval access plan expiration must be after its issue time."
        }
        require(expiresAtEpochMilli - issuedAtEpochMilli <= MAX_ACCESS_PLAN_TTL_MILLIS) {
            "Retrieval access plan exceeds the maximum lifetime."
        }
        require(this.authorizedDocumentIds.size <= RetrievalContractLimits.MAX_AUTHORIZED_DOCUMENTS) {
            "Retrieval access plan contains too many document identifiers."
        }
        this.authorizedDocumentIds.forEach { documentId ->
            requireRetrievalIdentifier(documentId, "Authorized document identifier is invalid.")
        }

        when (profile) {
            RetrievalAccessProfile.AUTHORIZED_ID_SET -> require(
                this.authorizedDocumentIds.isNotEmpty() && mandatoryFilter == null && backendNativeScope == null,
            ) { "Authorized-ID plans require a non-empty set and no other filter payload." }
            RetrievalAccessProfile.MANDATORY_FILTER -> require(
                this.authorizedDocumentIds.isEmpty() && mandatoryFilter != null && backendNativeScope == null,
            ) { "Mandatory-filter plans require exactly one typed filter payload." }
            RetrievalAccessProfile.BACKEND_NATIVE -> require(
                this.authorizedDocumentIds.isEmpty() && mandatoryFilter == null && backendNativeScope != null,
            ) { "Backend-native plans require exactly one provider-bound scope payload." }
            else -> throw IllegalArgumentException("Unknown retrieval access profile cannot be executed.")
        }

        filterDigest = when (profile) {
            RetrievalAccessProfile.AUTHORIZED_ID_SET -> retrievalDigest {
                text("flowweft-retrieval-authorized-document-filter-v1")
                val orderedIds = this@RetrievalAccessPlan.authorizedDocumentIds.map { it.value }.sorted()
                integer(orderedIds.size)
                orderedIds.forEach(::text)
            }
            RetrievalAccessProfile.MANDATORY_FILTER -> checkNotNull(mandatoryFilter).digest
            RetrievalAccessProfile.BACKEND_NATIVE -> checkNotNull(backendNativeScope).digest
            else -> error("Unknown retrieval access profile.")
        }
        scopeDigest = retrievalDigest {
            text("flowweft-retrieval-access-scope-v1")
            text(tenantId.value)
            text(principal.id.value)
            text(principal.type)
            text(subjectDigest)
            text(action)
            text(purposeCode)
            text(profile.id)
            text(authorizationAuthorityId)
            text(policyRevision)
            long(issuedAtEpochMilli)
            long(expiresAtEpochMilli)
            text(filterDigest)
        }
        digest = retrievalDigest {
            text("flowweft-retrieval-access-plan-v1")
            text(decisionId.value)
            text(authorizationRequestId.value)
            text(authorizationRequestDigest)
            text(scopeDigest)
        }
    }

    fun requireValidFor(request: RetrievalAuthorizationRequest, nowEpochMilli: Long) {
        require(nowEpochMilli >= 0L) { "Current time must not be negative." }
        require(authorizationRequestId == request.id && authorizationRequestDigest == request.digest) {
            "Retrieval access plan belongs to another authorization request."
        }
        require(tenantId == request.tenantId) { "Retrieval access plan belongs to another tenant." }
        require(principal == request.subject.principal && subjectDigest == request.subject.digest) {
            "Retrieval access plan belongs to another subject snapshot."
        }
        require(action == request.action && purposeCode == request.purposeCode) {
            "Retrieval access plan action or purpose does not match the authorization request."
        }
        require(issuedAtEpochMilli >= request.requestedAtEpochMilli) {
            "Retrieval access plan predates its authorization request."
        }
        require(issuedAtEpochMilli <= nowEpochMilli) { "Retrieval access plan was issued in the future." }
        require(nowEpochMilli < expiresAtEpochMilli) { "Retrieval access plan has expired." }
    }

    override fun toString(): String = "RetrievalAccessPlan(profile=${profile.id})"

    companion object {
        const val MAX_ACCESS_PLAN_TTL_MILLIS: Long = 300_000L

        @JvmStatic
        fun authorizedIds(
            decisionId: Identifier,
            request: RetrievalAuthorizationRequest,
            authorizationAuthorityId: String,
            policyRevision: String,
            issuedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
            authorizedDocumentIds: Collection<Identifier>,
        ): RetrievalAccessPlan = create(
            decisionId,
            request,
            RetrievalAccessProfile.AUTHORIZED_ID_SET,
            authorizationAuthorityId,
            policyRevision,
            issuedAtEpochMilli,
            expiresAtEpochMilli,
            authorizedDocumentIds,
            null,
            null,
        )

        @JvmStatic
        fun mandatoryFilter(
            decisionId: Identifier,
            request: RetrievalAuthorizationRequest,
            authorizationAuthorityId: String,
            policyRevision: String,
            issuedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
            mandatoryFilter: MandatoryFilter,
        ): RetrievalAccessPlan = create(
            decisionId,
            request,
            RetrievalAccessProfile.MANDATORY_FILTER,
            authorizationAuthorityId,
            policyRevision,
            issuedAtEpochMilli,
            expiresAtEpochMilli,
            emptyList(),
            mandatoryFilter,
            null,
        )

        @JvmStatic
        fun backendNative(
            decisionId: Identifier,
            request: RetrievalAuthorizationRequest,
            authorizationAuthorityId: String,
            policyRevision: String,
            issuedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
            backendNativeScope: BackendNativeScope,
        ): RetrievalAccessPlan {
            backendNativeScope.requireValidFor(request, issuedAtEpochMilli, expiresAtEpochMilli)
            return create(
                decisionId,
                request,
                RetrievalAccessProfile.BACKEND_NATIVE,
                authorizationAuthorityId,
                policyRevision,
                issuedAtEpochMilli,
                expiresAtEpochMilli,
                emptyList(),
                null,
                backendNativeScope,
            )
        }

        private fun create(
            decisionId: Identifier,
            request: RetrievalAuthorizationRequest,
            profile: RetrievalAccessProfile,
            authorizationAuthorityId: String,
            policyRevision: String,
            issuedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
            authorizedDocumentIds: Collection<Identifier>,
            mandatoryFilter: MandatoryFilter?,
            backendNativeScope: BackendNativeScope?,
        ): RetrievalAccessPlan = RetrievalAccessPlan(
            decisionId,
            request.id,
            request.digest,
            request.tenantId,
            request.subject.principal,
            request.subject.digest,
            request.action,
            request.purposeCode,
            profile,
            authorizationAuthorityId,
            policyRevision,
            issuedAtEpochMilli,
            expiresAtEpochMilli,
            authorizedDocumentIds,
            mandatoryFilter,
            backendNativeScope,
        )
    }
}

/** Stable denial reason. A denial never creates an executable provider request. */
class RetrievalDenialCode private constructor(val id: String) {
    init {
        requireRetrievalText(id, RetrievalContractLimits.MAX_ID_CODE_POINTS, "Retrieval denial code is invalid.")
        requireStableRetrievalCode(id, "Retrieval denial code must be a stable lower-case ASCII identifier.")
    }

    override fun equals(other: Any?): Boolean = other is RetrievalDenialCode && id == other.id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id

    companion object {
        @JvmField val POLICY_DENIED = RetrievalDenialCode("policy-denied")
        @JvmField val NO_VISIBLE_DOCUMENTS = RetrievalDenialCode("no-visible-documents")
        @JvmField val FILTER_NOT_REPRESENTABLE = RetrievalDenialCode("filter-not-representable")

        @JvmStatic
        fun of(id: String): RetrievalDenialCode = RetrievalDenialCode(id)
    }
}

/** Explicit allow/deny result bound to the original trusted authorization request. */
class RetrievalPlanResult private constructor(
    val decisionId: Identifier,
    val authorizationRequestId: Identifier,
    val authorizationRequestDigest: String,
    val authorizationAuthorityId: String,
    val policyRevision: String,
    val decidedAtEpochMilli: Long,
    private val plan: RetrievalAccessPlan?,
    val denialCode: RetrievalDenialCode?,
) {
    val allowed: Boolean = plan != null

    init {
        requireRetrievalIdentifier(decisionId, "Retrieval authorization decision identifier is invalid.")
        requireRetrievalIdentifier(authorizationRequestId, "Retrieval authorization request identifier is invalid.")
        requireDigest(authorizationRequestDigest, "Retrieval authorization request digest is invalid.")
        requireRetrievalText(
            authorizationAuthorityId,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Retrieval authorization authority identifier is invalid.",
        )
        requireRetrievalText(
            policyRevision,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Retrieval plan result policy revision is invalid.",
        )
        require(decidedAtEpochMilli >= 0L) { "Retrieval plan decision time must not be negative." }
        require((plan == null) == (denialCode != null)) {
            "Retrieval plan result must contain exactly one allow or deny outcome."
        }
    }

    fun requireAllowed(): RetrievalAccessPlan =
        plan ?: throw IllegalStateException("Retrieval authorization did not allow provider execution.")

    fun requireValidFor(request: RetrievalAuthorizationRequest) {
        require(authorizationRequestId == request.id && authorizationRequestDigest == request.digest) {
            "Retrieval plan result belongs to another authorization request."
        }
        require(decidedAtEpochMilli >= request.requestedAtEpochMilli) {
            "Retrieval plan decision predates its authorization request."
        }
        plan?.let { allowedPlan ->
            require(allowedPlan.decisionId == decisionId) {
                "Retrieval plan result decision identifier does not match its plan."
            }
            require(
                allowedPlan.authorizationAuthorityId == authorizationAuthorityId &&
                    allowedPlan.policyRevision == policyRevision,
            ) { "Retrieval plan result authority or policy revision does not match its plan." }
            require(decidedAtEpochMilli >= allowedPlan.issuedAtEpochMilli &&
                decidedAtEpochMilli < allowedPlan.expiresAtEpochMilli) {
                "Retrieval plan decision time is outside the plan validity window."
            }
        }
    }

    companion object {
        @JvmStatic
        fun allow(plan: RetrievalAccessPlan, decidedAtEpochMilli: Long): RetrievalPlanResult =
            RetrievalPlanResult(
                plan.decisionId,
                plan.authorizationRequestId,
                plan.authorizationRequestDigest,
                plan.authorizationAuthorityId,
                plan.policyRevision,
                decidedAtEpochMilli,
                plan,
                null,
            )

        @JvmStatic
        fun deny(
            decisionId: Identifier,
            request: RetrievalAuthorizationRequest,
            authorizationAuthorityId: String,
            policyRevision: String,
            decidedAtEpochMilli: Long,
            denialCode: RetrievalDenialCode,
        ): RetrievalPlanResult = RetrievalPlanResult(
            decisionId,
            request.id,
            request.digest,
            authorizationAuthorityId,
            policyRevision,
            decidedAtEpochMilli,
            null,
            denialCode,
        )
    }
}

/** Host authorization bridge. Implementations return an explicit allow or deny result. */
fun interface RetrievalAuthorizationPlanner {
    fun plan(request: RetrievalAuthorizationRequest): RetrievalPlanResult
}
