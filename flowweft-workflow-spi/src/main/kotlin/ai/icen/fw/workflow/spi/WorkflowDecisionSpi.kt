package ai.icen.fw.workflow.spi

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowPredicateRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import java.util.concurrent.CompletionStage

/** Provider-owned predicate descriptor. Its digest is bound by [WorkflowPredicateRef]. */
class WorkflowDecisionDescriptor private constructor(
    providerId: String,
    predicateId: String,
    version: String,
    val inputSchema: WorkflowSchemaRef,
    val deterministic: Boolean,
) {
    val providerId: String = WorkflowSpiContractSupport.requireMachineCode(providerId, "Workflow decision provider is invalid.")
    val predicateId: String = WorkflowSpiContractSupport.requireMachineCode(predicateId, "Workflow predicate identifier is invalid.")
    val version: String = WorkflowSpiContractSupport.requireText(
        version, WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES, "Workflow predicate version is invalid.",
    )
    val descriptorDigest: String = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-decision-descriptor-v1")
        .text(this.providerId)
        .text(this.predicateId)
        .text(this.version)
        .text(inputSchema.providerId)
        .text(inputSchema.schemaId)
        .text(inputSchema.version)
        .text(inputSchema.digest)
        .booleanValue(deterministic)
        .finish()

    override fun toString(): String = "WorkflowDecisionDescriptor(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            providerId: String,
            predicateId: String,
            version: String,
            inputSchema: WorkflowSchemaRef,
            deterministic: Boolean,
        ): WorkflowDecisionDescriptor = WorkflowDecisionDescriptor(
            providerId,
            predicateId,
            version,
            inputSchema,
            deterministic,
        )
    }
}

class WorkflowDecisionDescriptorRequest private constructor(
    val context: WorkflowProviderCallContext,
    val predicate: WorkflowPredicateRef,
) {
    val requestDigest: String

    init {
        require(context.providerId == predicate.providerId) { "Workflow predicate provider does not match the call context." }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-decision-describe-request-v1")
            .text(context.contextDigest)
            .text(predicate.providerId)
            .text(predicate.predicateId)
            .text(predicate.version)
            .text(predicate.digest)
            .text(predicate.bindingDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowDecisionDescriptorRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(context: WorkflowProviderCallContext, predicate: WorkflowPredicateRef): WorkflowDecisionDescriptorRequest =
            WorkflowDecisionDescriptorRequest(context, predicate)
    }
}

class WorkflowDecisionDescriptorResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val descriptor: WorkflowDecisionDescriptor?,
) {
    init {
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (descriptor != null)) {
            "Workflow decision descriptor result content does not match its outcome."
        }
    }

    override fun toString(): String = "WorkflowDecisionDescriptorResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowDecisionDescriptorRequest,
            descriptor: WorkflowDecisionDescriptor,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowDecisionDescriptorResult {
            require(descriptor.providerId == request.predicate.providerId &&
                descriptor.predicateId == request.predicate.predicateId &&
                descriptor.version == request.predicate.version &&
                descriptor.descriptorDigest == request.predicate.digest
            ) { "Workflow predicate descriptor does not match the exact predicate reference." }
            return WorkflowDecisionDescriptorResult(
                WorkflowProviderReceipt.success(
                    request.context,
                    request.requestDigest,
                    descriptor.descriptorDigest,
                    completedAtEpochMilli,
                    expiresAtEpochMilli,
                ),
                descriptor,
            )
        }

        @JvmStatic
        fun failure(
            request: WorkflowDecisionDescriptorRequest,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowDecisionDescriptorResult = WorkflowDecisionDescriptorResult(
            WorkflowProviderReceipt.failure(
                request.context,
                request.requestDigest,
                outcome,
                WorkflowSpiContractSupport.failureDigest("flowweft-workflow-spi-decision-describe-failure-v1", failure),
                failure,
                completedAtEpochMilli,
                expiresAtEpochMilli,
            ),
            null,
        )
    }
}

class WorkflowDecisionRequest private constructor(
    val context: WorkflowProviderCallContext,
    val definition: WorkflowDefinitionRef,
    val subject: WorkflowSubjectSnapshot,
    val actor: WorkflowPrincipalRef,
    val predicate: WorkflowPredicateRef,
    val descriptor: WorkflowDecisionDescriptor,
    val input: WorkflowStructuredPayload,
) {
    val requestDigest: String

    init {
        require(context.providerId == predicate.providerId) { "Workflow predicate provider does not match the call context." }
        require(descriptor.providerId == predicate.providerId && descriptor.predicateId == predicate.predicateId &&
            descriptor.version == predicate.version && descriptor.descriptorDigest == predicate.digest
        ) { "Workflow predicate descriptor does not match the exact predicate reference." }
        require(input.validated) { "Workflow predicate input requires trusted schema-validation evidence." }
        require(input.schema == descriptor.inputSchema) { "Workflow predicate input does not match the descriptor schema." }
        require(input.size <= context.maximumInputBytes) { "Workflow predicate input exceeds the call limit." }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-decision-request-v1")
            .text(context.contextDigest)
            .text(definition.key)
            .text(definition.version)
            .text(definition.digest)
            .text(subject.ref.type)
            .text(subject.ref.id)
            .text(subject.revision)
            .text(subject.digest)
            .text(actor.type)
            .text(actor.id)
            .text(predicate.providerId)
            .text(predicate.predicateId)
            .text(predicate.version)
            .text(predicate.digest)
            .text(predicate.bindingDigest)
            .text(descriptor.descriptorDigest)
            .text(input.contentDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowDecisionRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowProviderCallContext,
            definition: WorkflowDefinitionRef,
            subject: WorkflowSubjectSnapshot,
            actor: WorkflowPrincipalRef,
            predicate: WorkflowPredicateRef,
            descriptor: WorkflowDecisionDescriptor,
            input: WorkflowStructuredPayload,
        ): WorkflowDecisionRequest = WorkflowDecisionRequest(
            context,
            definition,
            subject,
            actor,
            predicate,
            descriptor,
            input,
        )
    }
}

/** Extensible decision value; unknown values are never treated as matched. */
class WorkflowDecisionOutcome private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(code, "Workflow decision outcome is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowDecisionOutcome && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowDecisionOutcome(<redacted>)"

    companion object {
        @JvmField val MATCHED = WorkflowDecisionOutcome("matched")
        @JvmField val NOT_MATCHED = WorkflowDecisionOutcome("not-matched")

        @JvmStatic
        fun of(code: String): WorkflowDecisionOutcome = when (code) {
            MATCHED.code -> MATCHED
            NOT_MATCHED.code -> NOT_MATCHED
            else -> WorkflowDecisionOutcome(code)
        }
    }
}

class WorkflowDecision private constructor(
    val outcome: WorkflowDecisionOutcome,
    ruleRevision: String,
    evidenceDigest: String,
) {
    val ruleRevision: String = WorkflowSpiContractSupport.requireText(
        ruleRevision, WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES, "Workflow rule revision is invalid.",
    )
    val evidenceDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        evidenceDigest, "Workflow decision evidence digest is invalid.",
    )
    val decisionDigest: String

    init {
        require(outcome == WorkflowDecisionOutcome.MATCHED || outcome == WorkflowDecisionOutcome.NOT_MATCHED) {
            "Unknown workflow decision outcomes require future typed runtime support."
        }
        decisionDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-decision-v1")
            .text(outcome.code)
            .text(this.ruleRevision)
            .text(this.evidenceDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowDecision(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            outcome: WorkflowDecisionOutcome,
            ruleRevision: String,
            evidenceDigest: String,
        ): WorkflowDecision = WorkflowDecision(outcome, ruleRevision, evidenceDigest)
    }
}

class WorkflowDecisionResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val decision: WorkflowDecision?,
) {
    init {
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (decision != null)) {
            "Workflow decision result content does not match its provider outcome."
        }
    }

    override fun toString(): String = "WorkflowDecisionResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowDecisionRequest,
            decision: WorkflowDecision,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowDecisionResult = WorkflowDecisionResult(
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
            request: WorkflowDecisionRequest,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowDecisionResult = WorkflowDecisionResult(
            WorkflowProviderReceipt.failure(
                request.context,
                request.requestDigest,
                outcome,
                WorkflowSpiContractSupport.failureDigest("flowweft-workflow-spi-decision-failure-v1", failure),
                failure,
                completedAtEpochMilli,
                expiresAtEpochMilli,
            ),
            null,
        )
    }
}

/** Tenant-aware predicate provider. Calls must complete before the bound deadline. */
interface WorkflowDecisionProvider {
    fun describe(request: WorkflowDecisionDescriptorRequest): CompletionStage<WorkflowDecisionDescriptorResult>
    fun evaluate(request: WorkflowDecisionRequest): CompletionStage<WorkflowDecisionResult>
}
