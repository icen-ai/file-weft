package ai.icen.fw.workflow.spi

import ai.icen.fw.workflow.api.WorkflowFormFieldAccessReport
import ai.icen.fw.workflow.api.WorkflowFormFieldPath
import ai.icen.fw.workflow.api.WorkflowFormVersionRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import java.util.concurrent.CompletionStage

/**
 * One compound schema-validation and field-authorization request. Keeping the checks in one
 * provider call prevents a trusted runtime from accepting normalized values under stale ACLs.
 */
class WorkflowSecureFormValidationRequest private constructor(
    val context: WorkflowProviderCallContext,
    val form: WorkflowFormVersionRef,
    val subject: WorkflowSubjectSnapshot,
    val actor: WorkflowPrincipalRef,
    val operation: WorkflowFormValidationOperation,
    requestedFields: Collection<WorkflowFormFieldPath>,
    val submission: WorkflowStructuredPayload,
    authorizationRevision: String,
    authorizationReceiptDigest: String,
) {
    val requestedFields: List<WorkflowFormFieldPath> = java.util.Collections.unmodifiableList(
        WorkflowSpiContractSupport.immutableList(
            requestedFields,
            WorkflowSpiContractSupport.MAX_ITEMS,
            "Workflow form field request exceeds the limit.",
        ).sortedBy { it.value },
    )
    val authorizationRevision: String = WorkflowSpiContractSupport.requireText(
        authorizationRevision,
        WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES,
        "Workflow form authorization revision is invalid.",
    )
    val authorizationReceiptDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        authorizationReceiptDigest,
        "Workflow form authorization receipt digest is invalid.",
    )
    val requestDigest: String

    init {
        require(operation == WorkflowFormValidationOperation.READ ||
            operation == WorkflowFormValidationOperation.SUBMIT
        ) { "Unknown workflow form validation operations fail closed." }
        require(this.requestedFields.isNotEmpty() &&
            this.requestedFields.toSet().size == this.requestedFields.size
        ) { "Workflow form field request must contain unique fields." }
        require(submission.size <= context.maximumInputBytes) {
            "Workflow form submission exceeds the provider input limit."
        }
        require(submission.schema.providerId == form.dataSchema.registryId &&
            submission.schema.schemaId == form.dataSchema.schemaId &&
            submission.schema.version == form.dataSchema.version &&
            submission.schema.digest == form.dataSchema.digest
        ) { "Workflow form submission is not bound to the exact JSON Schema reference." }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-secure-form-request-v1")
            .text(context.contextDigest)
            .text(form.bindingDigest)
            .text(subject.ref.type)
            .text(subject.ref.id)
            .text(subject.revision)
            .text(subject.digest)
            .text(actor.type)
            .text(actor.id)
            .text(operation.code)
            .integer(this.requestedFields.size)
            .also { writer -> this.requestedFields.forEach { writer.text(it.value) } }
            .text(submission.contentDigest)
            .text(this.authorizationRevision)
            .text(this.authorizationReceiptDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowSecureFormValidationRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowProviderCallContext,
            form: WorkflowFormVersionRef,
            subject: WorkflowSubjectSnapshot,
            actor: WorkflowPrincipalRef,
            operation: WorkflowFormValidationOperation,
            requestedFields: Collection<WorkflowFormFieldPath>,
            submission: WorkflowStructuredPayload,
            authorizationRevision: String,
            authorizationReceiptDigest: String,
        ): WorkflowSecureFormValidationRequest = WorkflowSecureFormValidationRequest(
            context,
            form,
            subject,
            actor,
            operation,
            requestedFields,
            submission,
            authorizationRevision,
            authorizationReceiptDigest,
        )
    }
}

class WorkflowSecureFormValidationReport private constructor(
    val schemaValid: Boolean,
    issues: Collection<WorkflowFormValidationIssue>,
    val normalizedSubmission: WorkflowStructuredPayload?,
    val fieldAccess: WorkflowFormFieldAccessReport,
) {
    val issues: List<WorkflowFormValidationIssue> = WorkflowSpiContractSupport.immutableList(
        issues,
        WorkflowSpiContractSupport.MAX_ISSUES,
        "Workflow secure form validation issues exceed the limit.",
    )
    val reportDigest: String

    init {
        require(schemaValid == this.issues.isEmpty()) {
            "Workflow secure form validity must match its issue list."
        }
        require(!schemaValid || normalizedSubmission != null && normalizedSubmission.validated) {
            "A valid workflow form requires normalized schema-validation evidence."
        }
        require(schemaValid || normalizedSubmission == null) {
            "An invalid workflow form cannot return normalized content."
        }
        reportDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-secure-form-report-v1")
            .booleanValue(schemaValid)
            .integer(this.issues.size)
            .also { writer -> this.issues.forEach { writer.text(it.issueDigest) } }
            .optionalText(normalizedSubmission?.contentDigest)
            .text(fieldAccess.reportDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowSecureFormValidationReport(<redacted>)"

    companion object {
        @JvmStatic
        fun valid(
            normalizedSubmission: WorkflowStructuredPayload,
            fieldAccess: WorkflowFormFieldAccessReport,
        ): WorkflowSecureFormValidationReport = WorkflowSecureFormValidationReport(
            true,
            emptyList(),
            normalizedSubmission,
            fieldAccess,
        )

        @JvmStatic
        fun invalid(
            issues: Collection<WorkflowFormValidationIssue>,
            fieldAccess: WorkflowFormFieldAccessReport,
        ): WorkflowSecureFormValidationReport = WorkflowSecureFormValidationReport(
            false,
            issues,
            null,
            fieldAccess,
        )
    }
}

class WorkflowSecureFormValidationResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val report: WorkflowSecureFormValidationReport?,
) {
    init {
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (report != null)) {
            "Workflow secure form result content does not match its outcome."
        }
    }

    override fun toString(): String = "WorkflowSecureFormValidationResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowSecureFormValidationRequest,
            report: WorkflowSecureFormValidationReport,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowSecureFormValidationResult {
            val decidedPaths = report.fieldAccess.decisions.map { it.path }
            require(decidedPaths.size == request.requestedFields.size &&
                decidedPaths.toSet() == request.requestedFields.toSet()
            ) { "Workflow field authorization must decide every requested path exactly once." }
            require(report.fieldAccess.authorityReceiptDigest == request.authorizationReceiptDigest) {
                "Workflow field authorization does not match the trusted authorization input."
            }
            require(report.issues.size <= request.context.maximumItems) {
                "Workflow secure form issue count exceeds the provider output limit."
            }
            require(report.normalizedSubmission == null ||
                report.normalizedSubmission.schema == request.submission.schema &&
                report.normalizedSubmission.size <= request.context.maximumOutputBytes
            ) { "Workflow normalized submission exceeds or changes the requested schema boundary." }
            return WorkflowSecureFormValidationResult(
                WorkflowProviderReceipt.success(
                    request.context,
                    request.requestDigest,
                    report.reportDigest,
                    completedAtEpochMilli,
                    expiresAtEpochMilli,
                ),
                report,
            )
        }

        @JvmStatic
        fun failure(
            request: WorkflowSecureFormValidationRequest,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowSecureFormValidationResult = WorkflowSecureFormValidationResult(
            WorkflowProviderReceipt.failure(
                request.context,
                request.requestDigest,
                outcome,
                WorkflowSpiContractSupport.failureDigest("flowweft-workflow-spi-secure-form-failure-v1", failure),
                failure,
                completedAtEpochMilli,
                expiresAtEpochMilli,
            ),
            null,
        )
    }
}

fun interface WorkflowSecureFormValidator {
    fun validate(request: WorkflowSecureFormValidationRequest): CompletionStage<WorkflowSecureFormValidationResult>
}
