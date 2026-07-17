package ai.icen.fw.workflow.document.selection

import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.document.DocumentWorkflowSelectionRequest
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import java.util.TreeMap

class DocumentSelectionAuthorizationPhase private constructor(code: String) {
    val code: String = DocumentSelectionSupport.code(code, "Document selection authorization phase")

    override fun equals(other: Any?): Boolean =
        this === other || other is DocumentSelectionAuthorizationPhase && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "DocumentSelectionAuthorizationPhase(<redacted>)"

    companion object {
        @JvmField val PREPARE = DocumentSelectionAuthorizationPhase("prepare")
        @JvmField val COMMIT = DocumentSelectionAuthorizationPhase("commit")
    }
}

class DocumentSelectionAuthorizationStatus private constructor(code: String) {
    val code: String = DocumentSelectionSupport.code(code, "Document selection authorization status")

    override fun equals(other: Any?): Boolean =
        this === other || other is DocumentSelectionAuthorizationStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "DocumentSelectionAuthorizationStatus(<redacted>)"

    companion object {
        @JvmField val AUTHORIZED = DocumentSelectionAuthorizationStatus("authorized")
        @JvmField val DENIED = DocumentSelectionAuthorizationStatus("denied")
    }
}

/** Exact authorization request; the caller's expected selection is only a claim, not policy fact. */
class DocumentSelectionAuthorizationRequest private constructor(
    val selectionRequest: DocumentWorkflowSelectionRequest,
    val phase: DocumentSelectionAuthorizationPhase,
) {
    val requestDigest: String = DocumentSelectionSupport.sha256(
        "flowweft-document-selection-authorization-request-v1",
        selectionRequest.requestDigest,
        phase.code,
        selectionRequest.callContext.tenantId,
        selectionRequest.callContext.actor.type,
        selectionRequest.callContext.actor.id,
        selectionRequest.subjectRecord.snapshot.ref.type,
        selectionRequest.subjectRecord.snapshot.ref.id,
        selectionRequest.subjectRecord.snapshot.revision,
        selectionRequest.subjectRecord.snapshot.digest,
        selectionRequest.expectedSelection.selectionDigest,
        selectionRequest.purposeDigest,
        selectionRequest.evaluatedAtEpochMilli.toString(),
    )

    override fun toString(): String = "DocumentSelectionAuthorizationRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            selectionRequest: DocumentWorkflowSelectionRequest,
            phase: DocumentSelectionAuthorizationPhase,
        ): DocumentSelectionAuthorizationRequest = DocumentSelectionAuthorizationRequest(
            selectionRequest,
            phase,
        )
    }
}

/** Short-lived current-authority evidence bound to one exact selection phase. */
class DocumentSelectionAuthorizationDecision private constructor(
    tenantId: String,
    val actor: WorkflowPrincipalRef,
    requestDigest: String,
    val status: DocumentSelectionAuthorizationStatus,
    authorityRevision: String,
    authorityDigest: String,
    evaluatedAtEpochMilli: Long,
    validUntilEpochMilli: Long,
) {
    val tenantId: String = DocumentSelectionSupport.text(tenantId, "Selection authorization tenant")
    val requestDigest: String = DocumentSelectionSupport.digest(
        requestDigest,
        "Selection authorization request digest",
    )
    val authorityRevision: String = DocumentSelectionSupport.text(
        authorityRevision,
        "Selection authorization authority revision",
        256,
    )
    val authorityDigest: String = DocumentSelectionSupport.digest(
        authorityDigest,
        "Selection authorization authority digest",
    )
    val evaluatedAtEpochMilli: Long = evaluatedAtEpochMilli.also {
        require(it >= 0L) { "Selection authorization evaluation time is invalid." }
    }
    val validUntilEpochMilli: Long = validUntilEpochMilli.also {
        require(it >= this.evaluatedAtEpochMilli) { "Selection authorization validity is invalid." }
    }
    val decisionDigest: String = DocumentSelectionSupport.sha256(
        "flowweft-document-selection-authorization-decision-v1",
        this.tenantId,
        actor.type,
        actor.id,
        this.requestDigest,
        status.code,
        this.authorityRevision,
        this.authorityDigest,
        this.evaluatedAtEpochMilli.toString(),
        this.validUntilEpochMilli.toString(),
    )

    init {
        require(status == DocumentSelectionAuthorizationStatus.AUTHORIZED ||
            status == DocumentSelectionAuthorizationStatus.DENIED
        ) { "Document selection authorization status is unsupported." }
    }

    fun matches(request: DocumentSelectionAuthorizationRequest): Boolean =
        tenantId == request.selectionRequest.callContext.tenantId &&
            actor == request.selectionRequest.callContext.actor &&
            requestDigest == request.requestDigest &&
            evaluatedAtEpochMilli <= request.selectionRequest.evaluatedAtEpochMilli &&
            validUntilEpochMilli >= request.selectionRequest.evaluatedAtEpochMilli

    override fun toString(): String = "DocumentSelectionAuthorizationDecision(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            actor: WorkflowPrincipalRef,
            requestDigest: String,
            status: DocumentSelectionAuthorizationStatus,
            authorityRevision: String,
            authorityDigest: String,
            evaluatedAtEpochMilli: Long,
            validUntilEpochMilli: Long,
        ): DocumentSelectionAuthorizationDecision = DocumentSelectionAuthorizationDecision(
            tenantId,
            actor,
            requestDigest,
            status,
            authorityRevision,
            authorityDigest,
            evaluatedAtEpochMilli,
            validUntilEpochMilli,
        )
    }
}

fun interface DocumentSelectionAuthorizationPort {
    /** Implementations re-read current tenant/principal authority for both PREPARE and COMMIT. */
    fun authorize(request: DocumentSelectionAuthorizationRequest): DocumentSelectionAuthorizationDecision
}

class DocumentSelectionFactsRequest private constructor(
    val selectionRequest: DocumentWorkflowSelectionRequest,
    authorizationDecisionDigest: String,
) {
    val authorizationDecisionDigest: String = DocumentSelectionSupport.digest(
        authorizationDecisionDigest,
        "Selection facts authorization decision digest",
    )
    val requestDigest: String = DocumentSelectionSupport.sha256(
        "flowweft-document-selection-facts-request-v1",
        selectionRequest.requestDigest,
        selectionRequest.subjectRecord.resolutionDigest,
        this.authorizationDecisionDigest,
    )

    override fun toString(): String = "DocumentSelectionFactsRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            selectionRequest: DocumentWorkflowSelectionRequest,
            authorizationDecisionDigest: String,
        ): DocumentSelectionFactsRequest = DocumentSelectionFactsRequest(
            selectionRequest,
            authorizationDecisionDigest,
        )
    }
}

/**
 * Authorization-filtered, host-authoritative facts used only for policy selection. Metadata must
 * be an explicit non-secret allow-list; catalog CRUD and raw host records never cross this port.
 */
class DocumentSelectionFacts private constructor(
    factsRequestDigest: String,
    tenantId: String,
    val actor: WorkflowPrincipalRef,
    val subject: WorkflowSubjectSnapshot,
    classification: String,
    metadata: Map<String, String>,
    authorityRevision: String,
    authorityDigest: String,
    resolvedAtEpochMilli: Long,
    validUntilEpochMilli: Long,
) {
    val factsRequestDigest: String = DocumentSelectionSupport.digest(
        factsRequestDigest,
        "Selection facts request digest",
    )
    val tenantId: String = DocumentSelectionSupport.text(tenantId, "Selection facts tenant")
    val classification: String = DocumentSelectionSupport.code(
        classification,
        "Document classification",
    )
    val metadata: Map<String, String> = immutableMetadata(metadata)
    val authorityRevision: String = DocumentSelectionSupport.text(
        authorityRevision,
        "Selection facts authority revision",
        256,
    )
    val authorityDigest: String = DocumentSelectionSupport.digest(
        authorityDigest,
        "Selection facts authority digest",
    )
    val resolvedAtEpochMilli: Long = resolvedAtEpochMilli.also {
        require(it >= 0L) { "Selection facts resolution time is invalid." }
    }
    val validUntilEpochMilli: Long = validUntilEpochMilli.also {
        require(it >= this.resolvedAtEpochMilli) { "Selection facts validity is invalid." }
    }
    val contentDigest: String
    val factsDigest: String

    init {
        val components = ArrayList<String?>()
        components += this.tenantId
        components += actor.type
        components += actor.id
        components += subject.ref.type
        components += subject.ref.id
        components += subject.revision
        components += subject.digest
        components += this.classification
        components += this.authorityRevision
        components += this.authorityDigest
        components += this.resolvedAtEpochMilli.toString()
        components += this.validUntilEpochMilli.toString()
        components += this.metadata.size.toString()
        this.metadata.forEach { (key, value) ->
            components += key
            components += value
        }
        contentDigest = DocumentSelectionSupport.sha256(
            "flowweft-document-selection-facts-content-v1",
            *components.toTypedArray(),
        )
        factsDigest = DocumentSelectionSupport.sha256(
            "flowweft-document-selection-facts-v1",
            this.factsRequestDigest,
            contentDigest,
        )
    }

    fun matches(request: DocumentSelectionFactsRequest): Boolean =
        factsRequestDigest == request.requestDigest &&
            tenantId == request.selectionRequest.callContext.tenantId &&
            actor == request.selectionRequest.callContext.actor &&
            subject == request.selectionRequest.subjectRecord.snapshot &&
            resolvedAtEpochMilli <= request.selectionRequest.evaluatedAtEpochMilli &&
            validUntilEpochMilli >= request.selectionRequest.evaluatedAtEpochMilli

    override fun toString(): String = "DocumentSelectionFacts(<redacted>)"

    companion object {
        @JvmStatic fun of(
            factsRequestDigest: String,
            tenantId: String,
            actor: WorkflowPrincipalRef,
            subject: WorkflowSubjectSnapshot,
            classification: String,
            metadata: Map<String, String>,
            authorityRevision: String,
            authorityDigest: String,
            resolvedAtEpochMilli: Long,
            validUntilEpochMilli: Long,
        ): DocumentSelectionFacts = DocumentSelectionFacts(
            factsRequestDigest,
            tenantId,
            actor,
            subject,
            classification,
            metadata,
            authorityRevision,
            authorityDigest,
            resolvedAtEpochMilli,
            validUntilEpochMilli,
        )

        private fun immutableMetadata(source: Map<String, String>): Map<String, String> {
            require(source.size <= DocumentSelectionSupport.MAX_METADATA_ENTRIES) {
                "Document selection metadata exceeds the entry limit."
            }
            val sorted = TreeMap<String, String>()
            var totalBytes = 0
            source.forEach { (key, value) ->
                val safeKey = DocumentSelectionSupport.code(key, "Document selection metadata key")
                val safeValue = DocumentSelectionSupport.text(
                    value,
                    "Document selection metadata value",
                    2_048,
                )
                totalBytes += safeKey.toByteArray(StandardCharsets.UTF_8).size
                totalBytes += safeValue.toByteArray(StandardCharsets.UTF_8).size
                sorted[safeKey] = safeValue
            }
            require(totalBytes <= DocumentSelectionSupport.MAX_METADATA_TOTAL_BYTES) {
                "Document selection metadata exceeds the byte limit."
            }
            return Collections.unmodifiableMap(LinkedHashMap(sorted))
        }
    }
}

fun interface DocumentSelectionFactsPort {
    fun resolve(request: DocumentSelectionFactsRequest): DocumentSelectionFacts?
}
