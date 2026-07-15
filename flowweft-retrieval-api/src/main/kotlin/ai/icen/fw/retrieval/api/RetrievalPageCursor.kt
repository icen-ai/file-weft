package ai.icen.fw.retrieval.api

import ai.icen.fw.core.id.Identifier

/**
 * Opaque provider continuation bound to one tenant, authorization scope, query, provider snapshot,
 * and index generation.
 *
 * The token is navigation state, never an authorization proof. Every resumed page still receives
 * a fresh query-level plan, exact tenant/access preselection receipt, authoritative lineage lookup,
 * and candidate-level authorization recheck. Hosts that expose cursors over HTTP should keep this
 * object server-side or wrap its state in an authenticated application cursor.
 */
class RetrievalPageCursor private constructor(
    val opaqueToken: String,
    val tenantId: Identifier,
    val mode: RetrievalMode,
    val queryDigest: String,
    val accessProfile: RetrievalAccessProfile,
    val filterDigest: String,
    val authorizationAuthorityId: String,
    val policyRevision: String,
    val providerTypeId: String,
    val providerInstanceId: String,
    val providerConfigurationDigest: String,
    val providerDescriptorDigest: String,
    val indexGeneration: String,
    val sourceAttemptId: Identifier,
    val sourceRequestDigest: String,
    val issuedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val digest: String

    init {
        requireOpaqueCursorToken(opaqueToken)
        requireRetrievalIdentifier(tenantId, "Retrieval cursor tenant identifier is invalid.")
        requireDigest(queryDigest, "Retrieval cursor query digest is invalid.")
        requireDigest(filterDigest, "Retrieval cursor filter digest is invalid.")
        listOf(authorizationAuthorityId, policyRevision, providerTypeId, providerInstanceId, indexGeneration)
            .forEach { value ->
                requireRetrievalText(
                    value,
                    RetrievalContractLimits.MAX_ID_CODE_POINTS,
                    "Retrieval cursor binding text is invalid.",
                )
            }
        requireDigest(providerConfigurationDigest, "Retrieval cursor provider configuration digest is invalid.")
        requireDigest(providerDescriptorDigest, "Retrieval cursor provider descriptor digest is invalid.")
        requireRetrievalIdentifier(sourceAttemptId, "Retrieval cursor source attempt identifier is invalid.")
        requireDigest(sourceRequestDigest, "Retrieval cursor source request digest is invalid.")
        require(issuedAtEpochMilli >= 0L && expiresAtEpochMilli > issuedAtEpochMilli) {
            "Retrieval cursor validity window is invalid."
        }
        require(expiresAtEpochMilli - issuedAtEpochMilli <= RetrievalAccessPlan.MAX_ACCESS_PLAN_TTL_MILLIS) {
            "Retrieval cursor lifetime exceeds the access-plan maximum."
        }
        digest = retrievalDigest {
            text("flowweft-retrieval-page-cursor-v1")
            text(opaqueToken)
            text(tenantId.value)
            text(mode.id)
            text(queryDigest)
            text(accessProfile.id)
            text(filterDigest)
            text(authorizationAuthorityId)
            text(policyRevision)
            text(providerTypeId)
            text(providerInstanceId)
            text(providerConfigurationDigest)
            text(providerDescriptorDigest)
            text(indexGeneration)
            text(sourceAttemptId.value)
            text(sourceRequestDigest)
            long(issuedAtEpochMilli)
            long(expiresAtEpochMilli)
        }
    }

    internal fun requireRequestShape(mode: RetrievalMode, query: String) {
        require(this.mode == mode && queryDigest == retrievalQueryDigest(mode, query)) {
            "Retrieval cursor belongs to another query or retrieval mode."
        }
    }

    internal fun requireValidFor(
        requestSpec: RetrievalRequestSpec,
        plan: RetrievalAccessPlan,
        descriptor: CandidateRetrieverDescriptor,
        nowEpochMilli: Long,
    ) {
        require(nowEpochMilli in issuedAtEpochMilli until expiresAtEpochMilli) {
            "Retrieval cursor has expired or is not yet valid."
        }
        requireRequestShape(requestSpec.mode, requestSpec.query)
        require(requestSpec.deadlineEpochMilli <= expiresAtEpochMilli) {
            "Resumed retrieval deadline exceeds the cursor validity window."
        }
        require(tenantId == plan.tenantId) { "Retrieval cursor belongs to another tenant." }
        require(accessProfile == plan.profile && filterDigest == plan.filterDigest) {
            "Retrieval cursor belongs to another authorization filter."
        }
        require(
            authorizationAuthorityId == plan.authorizationAuthorityId && policyRevision == plan.policyRevision,
        ) { "Retrieval cursor belongs to another authorization authority or policy revision." }
        require(
            providerTypeId == descriptor.providerTypeId &&
                providerInstanceId == descriptor.providerInstanceId &&
                providerConfigurationDigest == descriptor.configurationDigest &&
                providerDescriptorDigest == descriptor.digest,
        ) { "Retrieval cursor belongs to another provider identity or configuration." }
        require(descriptor.supportsCursorPagination) { "Provider does not support cursor pagination." }
    }

    override fun toString(): String = "RetrievalPageCursor(<redacted>)"

    companion object {
        @JvmSynthetic
        internal fun next(
            request: ExecutableRetrievalRequest,
            descriptor: CandidateRetrieverDescriptor,
            indexGeneration: String,
            opaqueToken: String,
            issuedAtEpochMilli: Long,
        ): RetrievalPageCursor {
            require(request.providerDescriptorDigest == descriptor.digest) {
                "Retrieval cursor source belongs to another provider descriptor."
            }
            require(descriptor.supportsCursorPagination) { "Provider does not support cursor pagination." }
            require(issuedAtEpochMilli >= request.preparedAtEpochMilli &&
                issuedAtEpochMilli < request.deadlineEpochMilli &&
                issuedAtEpochMilli < request.accessPlanExpiresAtEpochMilli) {
                "Retrieval cursor issue time is outside the source request validity window."
            }
            return RetrievalPageCursor(
                opaqueToken,
                request.tenantId,
                request.mode,
                retrievalQueryDigest(request.mode, request.query),
                request.accessProfile,
                request.filterDigest,
                request.authorizationAuthorityId,
                request.policyRevision,
                descriptor.providerTypeId,
                descriptor.providerInstanceId,
                descriptor.configurationDigest,
                descriptor.digest,
                indexGeneration,
                request.attemptId,
                request.digest,
                issuedAtEpochMilli,
                minOf(request.deadlineEpochMilli, request.accessPlanExpiresAtEpochMilli),
            )
        }
    }
}

internal fun retrievalQueryDigest(mode: RetrievalMode, query: String): String = retrievalDigest {
    text("flowweft-retrieval-query-v1")
    text(mode.id)
    text(query)
}

private fun requireOpaqueCursorToken(value: String) {
    require(value.length in 1..MAX_CURSOR_TOKEN_LENGTH &&
        value.all { character ->
            character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
                character == '.' || character == '_' || character == '~' ||
                character == '-'
        }) {
        "Retrieval cursor token must be a bounded URL-safe opaque value."
    }
}

private const val MAX_CURSOR_TOKEN_LENGTH: Int = 2_048
