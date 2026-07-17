package ai.icen.fw.workflow.document.selection

import ai.icen.fw.workflow.document.DocumentWorkflowSelection
import ai.icen.fw.workflow.document.DocumentWorkflowSelectionRequest

/** Exact provider/profile identity. Live aliases such as `latest` are not accepted as evidence. */
class DocumentSelectionPolicyDescriptor private constructor(
    providerId: String,
    profileId: String,
    profileVersion: String,
    profileDigest: String,
    capabilityRevision: String,
    configurationDigest: String,
) {
    val providerId: String = DocumentSelectionSupport.code(providerId, "Selection policy provider id")
    val profileId: String = DocumentSelectionSupport.code(profileId, "Selection policy profile id")
    val profileVersion: String = DocumentSelectionSupport.text(
        profileVersion,
        "Selection policy profile version",
        128,
    )
    val profileDigest: String = DocumentSelectionSupport.digest(
        profileDigest,
        "Selection policy profile digest",
    )
    val capabilityRevision: String = DocumentSelectionSupport.text(
        capabilityRevision,
        "Selection policy capability revision",
        128,
    )
    val configurationDigest: String = DocumentSelectionSupport.digest(
        configurationDigest,
        "Selection policy configuration digest",
    )
    val descriptorDigest: String = DocumentSelectionSupport.sha256(
        "flowweft-document-selection-policy-descriptor-v1",
        this.providerId,
        this.profileId,
        this.profileVersion,
        this.profileDigest,
        this.capabilityRevision,
        this.configurationDigest,
    )

    init {
        require(!this.profileVersion.equals("latest", ignoreCase = true)) {
            "Document selection policy profile must use an exact version."
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is DocumentSelectionPolicyDescriptor &&
            descriptorDigest == other.descriptorDigest

    override fun hashCode(): Int = descriptorDigest.hashCode()
    override fun toString(): String = "DocumentSelectionPolicyDescriptor(<redacted>)"

    companion object {
        @JvmStatic fun of(
            providerId: String,
            profileId: String,
            profileVersion: String,
            profileDigest: String,
            capabilityRevision: String,
            configurationDigest: String,
        ): DocumentSelectionPolicyDescriptor = DocumentSelectionPolicyDescriptor(
            providerId,
            profileId,
            profileVersion,
            profileDigest,
            capabilityRevision,
            configurationDigest,
        )
    }
}

class DocumentSelectionPolicyRequest private constructor(
    val selectionRequest: DocumentWorkflowSelectionRequest,
    val facts: DocumentSelectionFacts,
    val descriptor: DocumentSelectionPolicyDescriptor,
    authorizationDecisionDigest: String,
    authorizationRevision: String,
) {
    val authorizationDecisionDigest: String = DocumentSelectionSupport.digest(
        authorizationDecisionDigest,
        "Selection policy authorization decision digest",
    )
    val authorizationRevision: String = DocumentSelectionSupport.text(
        authorizationRevision,
        "Selection policy authorization revision",
        256,
    )
    val requestDigest: String = DocumentSelectionSupport.sha256(
        "flowweft-document-selection-policy-request-v1",
        selectionRequest.requestDigest,
        facts.factsDigest,
        descriptor.descriptorDigest,
        this.authorizationDecisionDigest,
        this.authorizationRevision,
        selectionRequest.expectedSelection.selectionDigest,
    )

    init {
        require(facts.tenantId == selectionRequest.callContext.tenantId &&
            facts.actor == selectionRequest.callContext.actor &&
            facts.subject == selectionRequest.subjectRecord.snapshot
        ) { "Document selection policy facts do not belong to the trusted request." }
    }

    override fun toString(): String = "DocumentSelectionPolicyRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            selectionRequest: DocumentWorkflowSelectionRequest,
            facts: DocumentSelectionFacts,
            descriptor: DocumentSelectionPolicyDescriptor,
            authorizationDecisionDigest: String,
            authorizationRevision: String,
        ): DocumentSelectionPolicyRequest = DocumentSelectionPolicyRequest(
            selectionRequest,
            facts,
            descriptor,
            authorizationDecisionDigest,
            authorizationRevision,
        )
    }
}

class DocumentSelectionPolicyStatus private constructor(code: String) {
    val code: String = DocumentSelectionSupport.code(code, "Document selection policy status")

    override fun equals(other: Any?): Boolean =
        this === other || other is DocumentSelectionPolicyStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "DocumentSelectionPolicyStatus(<redacted>)"

    companion object {
        @JvmField val SELECTED = DocumentSelectionPolicyStatus("selected")
        @JvmField val UNSUPPORTED = DocumentSelectionPolicyStatus("unsupported")
    }
}

/** Exact policy result. `UNSUPPORTED` is the safe answer for an unknown classification. */
class DocumentSelectionPolicyResult private constructor(
    requestDigest: String,
    descriptorDigest: String,
    val status: DocumentSelectionPolicyStatus,
    val selection: DocumentWorkflowSelection?,
    policyRevision: String,
    policyEvidenceDigest: String,
    failureCode: String?,
    evaluatedAtEpochMilli: Long,
    validUntilEpochMilli: Long,
) {
    val requestDigest: String = DocumentSelectionSupport.digest(
        requestDigest,
        "Selection policy request digest",
    )
    val descriptorDigest: String = DocumentSelectionSupport.digest(
        descriptorDigest,
        "Selection policy descriptor digest",
    )
    val policyRevision: String = DocumentSelectionSupport.text(
        policyRevision,
        "Selection policy evaluation revision",
        256,
    )
    val policyEvidenceDigest: String = DocumentSelectionSupport.digest(
        policyEvidenceDigest,
        "Selection policy evidence digest",
    )
    val failureCode: String? = failureCode?.let {
        DocumentSelectionSupport.code(it, "Selection policy failure code")
    }
    val evaluatedAtEpochMilli: Long = evaluatedAtEpochMilli.also {
        require(it >= 0L) { "Selection policy evaluation time is invalid." }
    }
    val validUntilEpochMilli: Long = validUntilEpochMilli.also {
        require(it >= this.evaluatedAtEpochMilli) { "Selection policy validity is invalid." }
    }

    init {
        require(status == DocumentSelectionPolicyStatus.SELECTED ||
            status == DocumentSelectionPolicyStatus.UNSUPPORTED
        ) { "Document selection policy status is unsupported." }
        require((status == DocumentSelectionPolicyStatus.SELECTED) == (selection != null)) {
            "Document selection policy result shape is invalid."
        }
        require(status == DocumentSelectionPolicyStatus.SELECTED || this.failureCode != null) {
            "Unsupported document selection requires a stable code."
        }
    }

    fun matches(request: DocumentSelectionPolicyRequest): Boolean =
        requestDigest == request.requestDigest &&
            descriptorDigest == request.descriptor.descriptorDigest &&
            evaluatedAtEpochMilli <= request.selectionRequest.evaluatedAtEpochMilli &&
            validUntilEpochMilli >= request.selectionRequest.evaluatedAtEpochMilli

    override fun toString(): String = "DocumentSelectionPolicyResult(<redacted>)"

    companion object {
        @JvmStatic fun selected(
            requestDigest: String,
            descriptorDigest: String,
            selection: DocumentWorkflowSelection,
            policyRevision: String,
            policyEvidenceDigest: String,
            evaluatedAtEpochMilli: Long,
            validUntilEpochMilli: Long,
        ): DocumentSelectionPolicyResult = DocumentSelectionPolicyResult(
            requestDigest,
            descriptorDigest,
            DocumentSelectionPolicyStatus.SELECTED,
            selection,
            policyRevision,
            policyEvidenceDigest,
            null,
            evaluatedAtEpochMilli,
            validUntilEpochMilli,
        )

        @JvmStatic fun unsupported(
            requestDigest: String,
            descriptorDigest: String,
            policyRevision: String,
            policyEvidenceDigest: String,
            failureCode: String,
            evaluatedAtEpochMilli: Long,
            validUntilEpochMilli: Long,
        ): DocumentSelectionPolicyResult = DocumentSelectionPolicyResult(
            requestDigest,
            descriptorDigest,
            DocumentSelectionPolicyStatus.UNSUPPORTED,
            null,
            policyRevision,
            policyEvidenceDigest,
            failureCode,
            evaluatedAtEpochMilli,
            validUntilEpochMilli,
        )
    }
}

interface DocumentSelectionPolicyProvider {
    fun descriptor(): DocumentSelectionPolicyDescriptor
    fun select(request: DocumentSelectionPolicyRequest): DocumentSelectionPolicyResult
}

/** Canonical authority revision that an exact selected [DocumentWorkflowSelection] must carry. */
class DocumentSelectionAuthority private constructor() {
    companion object {
        @JvmStatic fun revision(
            descriptor: DocumentSelectionPolicyDescriptor,
            facts: DocumentSelectionFacts,
            policyRevision: String,
            authorizationRevision: String,
        ): String = DocumentSelectionSupport.sha256(
            "flowweft-document-selection-authority-revision-v1",
            descriptor.descriptorDigest,
            facts.contentDigest,
            DocumentSelectionSupport.text(
                policyRevision,
                "Selection policy evaluation revision",
                256,
            ),
            DocumentSelectionSupport.text(
                authorizationRevision,
                "Selection authorization authority revision",
                256,
            ),
        )
    }
}
