package ai.icen.fw.workflow.spi

import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import java.util.concurrent.CompletionStage

class WorkflowFormRef private constructor(
    providerId: String,
    formId: String,
    version: String,
    digest: String,
) {
    val providerId: String = WorkflowSpiContractSupport.requireMachineCode(providerId, "Workflow form provider is invalid.")
    val formId: String = WorkflowSpiContractSupport.requireMachineCode(formId, "Workflow form identifier is invalid.")
    val version: String = WorkflowSpiContractSupport.requireText(
        version, WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES, "Workflow form version is invalid.",
    )
    val digest: String = WorkflowSpiContractSupport.requireCanonicalSha256(digest, "Workflow form digest is invalid.")

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowFormRef && providerId == other.providerId && formId == other.formId &&
        version == other.version && digest == other.digest

    override fun hashCode(): Int {
        var result = providerId.hashCode()
        result = 31 * result + formId.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + digest.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowFormRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(providerId: String, formId: String, version: String, digest: String): WorkflowFormRef =
            WorkflowFormRef(providerId, formId, version, digest)
    }
}

/** Exact JSON Schema and UI schema binding used by a versioned form. */
class WorkflowFormDescriptor private constructor(
    providerId: String,
    formId: String,
    version: String,
    val dataSchema: WorkflowSchemaRef,
    val uiSchema: WorkflowSchemaRef?,
    val maximumSubmissionBytes: Int,
    val maximumFields: Int,
) {
    val providerId: String = WorkflowSpiContractSupport.requireMachineCode(providerId, "Workflow form provider is invalid.")
    val formId: String = WorkflowSpiContractSupport.requireMachineCode(formId, "Workflow form identifier is invalid.")
    val version: String = WorkflowSpiContractSupport.requireText(
        version, WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES, "Workflow form version is invalid.",
    )
    val descriptorDigest: String
    val ref: WorkflowFormRef

    init {
        require(maximumSubmissionBytes in 1..WorkflowSpiContractSupport.MAX_PAYLOAD_BYTES) {
            "Workflow form submission byte limit is invalid."
        }
        require(maximumFields in 1..WorkflowSpiContractSupport.MAX_ITEMS) {
            "Workflow form field limit is invalid."
        }
        descriptorDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-form-descriptor-v1")
            .text(this.providerId)
            .text(this.formId)
            .text(this.version)
            .text(dataSchema.providerId)
            .text(dataSchema.schemaId)
            .text(dataSchema.version)
            .text(dataSchema.digest)
            .optionalText(uiSchema?.providerId)
            .optionalText(uiSchema?.schemaId)
            .optionalText(uiSchema?.version)
            .optionalText(uiSchema?.digest)
            .integer(maximumSubmissionBytes)
            .integer(maximumFields)
            .finish()
        ref = WorkflowFormRef.of(this.providerId, this.formId, this.version, descriptorDigest)
    }

    override fun toString(): String = "WorkflowFormDescriptor(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            providerId: String,
            formId: String,
            version: String,
            dataSchema: WorkflowSchemaRef,
            uiSchema: WorkflowSchemaRef?,
            maximumSubmissionBytes: Int,
            maximumFields: Int,
        ): WorkflowFormDescriptor = WorkflowFormDescriptor(
            providerId,
            formId,
            version,
            dataSchema,
            uiSchema,
            maximumSubmissionBytes,
            maximumFields,
        )
    }
}

class WorkflowFormDescriptorRequest private constructor(
    val context: WorkflowProviderCallContext,
    val form: WorkflowFormRef,
) {
    val requestDigest: String

    init {
        require(context.providerId == form.providerId) { "Workflow form provider does not match the call context." }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-form-descriptor-request-v1")
            .text(context.contextDigest)
            .text(form.providerId)
            .text(form.formId)
            .text(form.version)
            .text(form.digest)
            .finish()
    }

    override fun toString(): String = "WorkflowFormDescriptorRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(context: WorkflowProviderCallContext, form: WorkflowFormRef): WorkflowFormDescriptorRequest =
            WorkflowFormDescriptorRequest(context, form)
    }
}

class WorkflowFormDescriptorResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val descriptor: WorkflowFormDescriptor?,
) {
    init {
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (descriptor != null)) {
            "Workflow form descriptor result content does not match its outcome."
        }
    }

    override fun toString(): String = "WorkflowFormDescriptorResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowFormDescriptorRequest,
            descriptor: WorkflowFormDescriptor,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowFormDescriptorResult {
            require(descriptor.ref == request.form) { "Workflow form descriptor does not match the exact form reference." }
            return WorkflowFormDescriptorResult(
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
            request: WorkflowFormDescriptorRequest,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowFormDescriptorResult = WorkflowFormDescriptorResult(
            WorkflowProviderReceipt.failure(
                request.context,
                request.requestDigest,
                outcome,
                WorkflowSpiContractSupport.failureDigest("flowweft-workflow-spi-form-descriptor-failure-v1", failure),
                failure,
                completedAtEpochMilli,
                expiresAtEpochMilli,
            ),
            null,
        )
    }
}

class WorkflowFormValidationOperation private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(code, "Workflow form operation is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowFormValidationOperation && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowFormValidationOperation(<redacted>)"

    companion object {
        @JvmField val READ = WorkflowFormValidationOperation("read")
        @JvmField val SUBMIT = WorkflowFormValidationOperation("submit")

        @JvmStatic
        fun of(code: String): WorkflowFormValidationOperation = when (code) {
            READ.code -> READ
            SUBMIT.code -> SUBMIT
            else -> WorkflowFormValidationOperation(code)
        }
    }
}

class WorkflowFormValidationRequest private constructor(
    val context: WorkflowProviderCallContext,
    val descriptor: WorkflowFormDescriptor,
    val subject: WorkflowSubjectSnapshot,
    val actor: WorkflowPrincipalRef,
    val operation: WorkflowFormValidationOperation,
    val submission: WorkflowStructuredPayload,
) {
    val requestDigest: String

    init {
        require(context.providerId == descriptor.providerId) { "Workflow form provider does not match the call context." }
        require(operation == WorkflowFormValidationOperation.READ || operation == WorkflowFormValidationOperation.SUBMIT) {
            "Unknown workflow form operations require future typed support."
        }
        require(submission.schema == descriptor.dataSchema && submission.size <= descriptor.maximumSubmissionBytes &&
            submission.size <= context.maximumInputBytes
        ) { "Workflow form submission does not match the descriptor or call limits." }
        require(!submission.validated || submission.fieldCount!! <= descriptor.maximumFields) {
            "Workflow form submission exceeds the descriptor field limit."
        }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-form-validation-request-v1")
            .text(context.contextDigest)
            .text(descriptor.descriptorDigest)
            .text(subject.ref.type)
            .text(subject.ref.id)
            .text(subject.revision)
            .text(subject.digest)
            .text(actor.type)
            .text(actor.id)
            .text(operation.code)
            .text(submission.contentDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowFormValidationRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowProviderCallContext,
            descriptor: WorkflowFormDescriptor,
            subject: WorkflowSubjectSnapshot,
            actor: WorkflowPrincipalRef,
            operation: WorkflowFormValidationOperation,
            submission: WorkflowStructuredPayload,
        ): WorkflowFormValidationRequest = WorkflowFormValidationRequest(
            context,
            descriptor,
            subject,
            actor,
            operation,
            submission,
        )
    }
}

/** Value-free validation issue; [path] is a JSON Pointer-like data path, never executable code. */
class WorkflowFormValidationIssue private constructor(path: String, code: String) {
    val path: String = WorkflowSpiContractSupport.requireText(
        path, WorkflowSpiContractSupport.MAX_TEXT_UTF8_BYTES, "Workflow form issue path is invalid.",
    )
    val code: String = WorkflowSpiContractSupport.requireMachineCode(code, "Workflow form issue code is invalid.")
    val issueDigest: String = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-form-issue-v1")
        .text(this.path)
        .text(this.code)
        .finish()

    override fun toString(): String = "WorkflowFormValidationIssue(<redacted>)"

    companion object {
        @JvmStatic
        fun of(path: String, code: String): WorkflowFormValidationIssue = WorkflowFormValidationIssue(path, code)
    }
}

class WorkflowFormValidationReport private constructor(
    val valid: Boolean,
    issues: Collection<WorkflowFormValidationIssue>,
    val normalizedSubmission: WorkflowStructuredPayload?,
) {
    val issues: List<WorkflowFormValidationIssue> = WorkflowSpiContractSupport.immutableList(
        issues,
        WorkflowSpiContractSupport.MAX_ISSUES,
        "Workflow form validation issues exceed the limit.",
    )
    val reportDigest: String

    init {
        require(valid == this.issues.isEmpty()) { "Workflow form validity must match its issue list." }
        require(valid || normalizedSubmission == null) { "Invalid workflow forms cannot return normalized data." }
        require(!valid || normalizedSubmission != null && normalizedSubmission.validated) {
            "Valid workflow forms require a normalized submission with trusted validation evidence."
        }
        reportDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-form-report-v1")
            .booleanValue(valid)
            .integer(this.issues.size)
            .also { writer -> this.issues.forEach { issue -> writer.text(issue.issueDigest) } }
            .optionalText(normalizedSubmission?.contentDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowFormValidationReport(<redacted>)"

    companion object {
        @JvmStatic
        fun valid(normalizedSubmission: WorkflowStructuredPayload): WorkflowFormValidationReport =
            WorkflowFormValidationReport(true, emptyList(), normalizedSubmission)

        @JvmStatic
        fun invalid(issues: Collection<WorkflowFormValidationIssue>): WorkflowFormValidationReport =
            WorkflowFormValidationReport(false, issues, null)
    }
}

class WorkflowFormValidationResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val report: WorkflowFormValidationReport?,
) {
    init {
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (report != null)) {
            "Workflow form validation result content does not match its outcome."
        }
    }

    override fun toString(): String = "WorkflowFormValidationResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowFormValidationRequest,
            report: WorkflowFormValidationReport,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowFormValidationResult {
            require(report.issues.size <= request.context.maximumItems) {
                "Workflow form validation issue count exceeds the call limit."
            }
            require(report.normalizedSubmission == null ||
                report.normalizedSubmission.schema == request.descriptor.dataSchema &&
                report.normalizedSubmission.size <= request.descriptor.maximumSubmissionBytes &&
                report.normalizedSubmission.size <= request.context.maximumOutputBytes &&
                report.normalizedSubmission.validated &&
                report.normalizedSubmission.fieldCount!! <= request.descriptor.maximumFields
            ) { "Workflow normalized form submission does not match the descriptor or call limits." }
            return WorkflowFormValidationResult(
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
            request: WorkflowFormValidationRequest,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowFormValidationResult = WorkflowFormValidationResult(
            WorkflowProviderReceipt.failure(
                request.context,
                request.requestDigest,
                outcome,
                WorkflowSpiContractSupport.failureDigest("flowweft-workflow-spi-form-validation-failure-v1", failure),
                failure,
                completedAtEpochMilli,
                expiresAtEpochMilli,
            ),
            null,
        )
    }
}

interface WorkflowFormProvider {
    fun describe(request: WorkflowFormDescriptorRequest): CompletionStage<WorkflowFormDescriptorResult>
    fun validate(request: WorkflowFormValidationRequest): CompletionStage<WorkflowFormValidationResult>
}
