package ai.icen.fw.workflow.spi

import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import java.util.concurrent.CompletionStage

/** Bounded request for host-authoritative subject attributes. */
class WorkflowSubjectProjectionRequest private constructor(
    val context: WorkflowProviderCallContext,
    val subject: WorkflowSubjectRef,
    val actor: WorkflowPrincipalRef,
    expectedRevision: String?,
    requestedAttributes: Collection<String>,
) {
    val expectedRevision: String? = expectedRevision?.let {
        WorkflowSpiContractSupport.requireText(
            it,
            WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES,
            "Workflow subject expected revision is invalid.",
        )
    }
    val requestedAttributes: List<String> = WorkflowSpiContractSupport.immutableList(
        requestedAttributes.map { attribute ->
            WorkflowSpiContractSupport.requireMachineCode(attribute, "Workflow subject attribute identifier is invalid.")
        },
        context.maximumItems,
        "Workflow subject attribute request exceeds the limit.",
    )
    val requestDigest: String

    init {
        require(this.requestedAttributes.isNotEmpty()) { "Workflow subject projection requires attributes." }
        require(this.requestedAttributes.toSet().size == this.requestedAttributes.size) {
            "Workflow subject attributes must be unique."
        }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-subject-request-v1")
            .text(context.contextDigest)
            .text(subject.type)
            .text(subject.id)
            .text(actor.type)
            .text(actor.id)
            .optionalText(this.expectedRevision)
            .integer(this.requestedAttributes.size)
            .also { writer -> this.requestedAttributes.forEach(writer::text) }
            .finish()
    }

    override fun toString(): String = "WorkflowSubjectProjectionRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowProviderCallContext,
            subject: WorkflowSubjectRef,
            actor: WorkflowPrincipalRef,
            expectedRevision: String?,
            requestedAttributes: Collection<String>,
        ): WorkflowSubjectProjectionRequest = WorkflowSubjectProjectionRequest(
            context,
            subject,
            actor,
            expectedRevision,
            requestedAttributes,
        )
    }
}

/** Exact, non-secret subject projection returned by the configured host authority. */
class WorkflowSubjectProjection private constructor(
    val snapshot: WorkflowSubjectSnapshot,
    projectedAttributes: Collection<String>,
    val data: WorkflowStructuredPayload,
) {
    val projectedAttributes: List<String> = WorkflowSpiContractSupport.immutableList(
        projectedAttributes.map { attribute ->
            WorkflowSpiContractSupport.requireMachineCode(attribute, "Workflow projected attribute identifier is invalid.")
        },
        WorkflowSpiContractSupport.MAX_ITEMS,
        "Workflow projected attributes exceed the limit.",
    )
    val projectionDigest: String

    init {
        require(this.projectedAttributes.isNotEmpty()) { "Workflow subject projections require attributes." }
        require(this.projectedAttributes.toSet().size == this.projectedAttributes.size) {
            "Workflow projected attributes must be unique."
        }
        require(data.validated) { "Workflow subject projection data requires trusted schema-validation evidence." }
        projectionDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-subject-projection-v1")
            .text(snapshot.ref.type)
            .text(snapshot.ref.id)
            .text(snapshot.revision)
            .text(snapshot.digest)
            .integer(this.projectedAttributes.size)
            .also { writer -> this.projectedAttributes.forEach(writer::text) }
            .text(data.contentDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowSubjectProjection(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            snapshot: WorkflowSubjectSnapshot,
            projectedAttributes: Collection<String>,
            data: WorkflowStructuredPayload,
        ): WorkflowSubjectProjection = WorkflowSubjectProjection(snapshot, projectedAttributes, data)
    }
}

/** Typed subject response carrying a provider invocation receipt. */
class WorkflowSubjectProjectionResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val projection: WorkflowSubjectProjection?,
) {
    init {
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (projection != null)) {
            "Workflow subject result content does not match its provider outcome."
        }
    }

    override fun toString(): String = "WorkflowSubjectProjectionResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowSubjectProjectionRequest,
            projection: WorkflowSubjectProjection,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowSubjectProjectionResult {
            require(projection.snapshot.ref == request.subject) { "Workflow subject resolver returned another subject." }
            require(request.expectedRevision == null || projection.snapshot.revision == request.expectedRevision) {
                "Workflow subject resolver returned another revision."
            }
            require(projection.projectedAttributes == request.requestedAttributes) {
                "Workflow subject resolver must return the exact requested attribute projection."
            }
            require(projection.data.size <= request.context.maximumOutputBytes) {
                "Workflow subject projection exceeds the requested output limit."
            }
            return WorkflowSubjectProjectionResult(
                WorkflowProviderReceipt.success(
                    request.context,
                    request.requestDigest,
                    projection.projectionDigest,
                    completedAtEpochMilli,
                    expiresAtEpochMilli,
                ),
                projection,
            )
        }

        @JvmStatic
        fun failure(
            request: WorkflowSubjectProjectionRequest,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowSubjectProjectionResult = WorkflowSubjectProjectionResult(
            WorkflowProviderReceipt.failure(
                request.context,
                request.requestDigest,
                outcome,
                WorkflowSpiContractSupport.failureDigest("flowweft-workflow-spi-subject-failure-v1", failure),
                failure,
                completedAtEpochMilli,
                expiresAtEpochMilli,
            ),
            null,
        )
    }
}

/** Host boundary for resolving trusted subject state; it performs no subject mutation. */
fun interface WorkflowSubjectProjectionResolver {
    fun resolve(request: WorkflowSubjectProjectionRequest): CompletionStage<WorkflowSubjectProjectionResult>
}
