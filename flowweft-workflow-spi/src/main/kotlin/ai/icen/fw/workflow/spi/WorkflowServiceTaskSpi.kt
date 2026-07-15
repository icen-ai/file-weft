package ai.icen.fw.workflow.spi

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowInstanceRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import java.util.concurrent.CompletionStage

class WorkflowServiceTaskIdempotencyMode private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(code, "Workflow service-task idempotency mode is invalid.")
    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowServiceTaskIdempotencyMode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowServiceTaskIdempotencyMode(<redacted>)"

    companion object {
        @JvmField val REQUIRED = WorkflowServiceTaskIdempotencyMode("required")
        @JvmField val PROVIDER_GUARANTEED = WorkflowServiceTaskIdempotencyMode("provider-guaranteed")
        @JvmStatic fun of(code: String): WorkflowServiceTaskIdempotencyMode = when (code) {
            REQUIRED.code -> REQUIRED
            PROVIDER_GUARANTEED.code -> PROVIDER_GUARANTEED
            else -> WorkflowServiceTaskIdempotencyMode(code)
        }
    }
}

class WorkflowServiceTaskRetryPolicy private constructor(
    val maximumAttempts: Int,
    val initialDelayMillis: Long,
    val maximumDelayMillis: Long,
    val backoffPermille: Int,
    retryableFailureCodes: Collection<String>,
) {
    val retryableFailureCodes: List<String> = WorkflowSpiContractSupport.immutableList(
        retryableFailureCodes.map {
            WorkflowSpiContractSupport.requireMachineCode(it, "Workflow service-task retry failure code is invalid.")
        },
        WorkflowSpiContractSupport.MAX_ITEMS,
        "Workflow service-task retry failure codes exceed the limit.",
    )
    val policyDigest: String

    init {
        require(maximumAttempts in 1..20) { "Workflow service-task attempt limit is invalid." }
        require(initialDelayMillis in 0L..WorkflowSpiContractSupport.MAX_CALL_WINDOW_MILLIS) {
            "Workflow service-task initial retry delay is invalid."
        }
        require(maximumDelayMillis in initialDelayMillis..86_400_000L) {
            "Workflow service-task maximum retry delay is invalid."
        }
        require(backoffPermille in 1_000..10_000) { "Workflow service-task retry backoff is invalid." }
        require(this.retryableFailureCodes.toSet().size == this.retryableFailureCodes.size) {
            "Workflow service-task retry failure codes must be unique."
        }
        policyDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-service-retry-policy-v1")
            .integer(maximumAttempts)
            .longValue(initialDelayMillis)
            .longValue(maximumDelayMillis)
            .integer(backoffPermille)
            .integer(this.retryableFailureCodes.size)
            .also { writer -> this.retryableFailureCodes.forEach(writer::text) }
            .finish()
    }

    override fun toString(): String = "WorkflowServiceTaskRetryPolicy(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            maximumAttempts: Int,
            initialDelayMillis: Long,
            maximumDelayMillis: Long,
            backoffPermille: Int,
            retryableFailureCodes: Collection<String>,
        ): WorkflowServiceTaskRetryPolicy = WorkflowServiceTaskRetryPolicy(
            maximumAttempts, initialDelayMillis, maximumDelayMillis, backoffPermille, retryableFailureCodes,
        )
    }
}

class WorkflowServiceTaskCompensationDescriptor private constructor(
    handlerId: String,
    version: String,
    digest: String,
) {
    val handlerId: String = WorkflowSpiContractSupport.requireMachineCode(
        handlerId, "Workflow service-task compensation handler is invalid.",
    )
    val version: String = WorkflowSpiContractSupport.requireText(
        version, WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES, "Workflow compensation handler version is invalid.",
    )
    val digest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        digest, "Workflow compensation descriptor digest is invalid.",
    )

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowServiceTaskCompensationDescriptor && handlerId == other.handlerId &&
        version == other.version && digest == other.digest
    override fun hashCode(): Int = 31 * (31 * handlerId.hashCode() + version.hashCode()) + digest.hashCode()
    override fun toString(): String = "WorkflowServiceTaskCompensationDescriptor(<redacted>)"

    companion object {
        @JvmStatic
        fun of(handlerId: String, version: String, digest: String): WorkflowServiceTaskCompensationDescriptor =
            WorkflowServiceTaskCompensationDescriptor(handlerId, version, digest)
    }
}

/** Host-managed outbound-network policy identity. It never contains a URL, host, or credential. */
class WorkflowEgressProfileRef private constructor(profileId: String, revision: String, digest: String) {
    val profileId: String = WorkflowSpiContractSupport.requireMachineCode(profileId, "Workflow egress profile is invalid.")
    val revision: String = WorkflowSpiContractSupport.requireText(
        revision, WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES, "Workflow egress profile revision is invalid.",
    )
    val digest: String = WorkflowSpiContractSupport.requireCanonicalSha256(digest, "Workflow egress profile digest is invalid.")
    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowEgressProfileRef && profileId == other.profileId && revision == other.revision && digest == other.digest
    override fun hashCode(): Int = 31 * (31 * profileId.hashCode() + revision.hashCode()) + digest.hashCode()
    override fun toString(): String = "WorkflowEgressProfileRef(<redacted>)"

    companion object {
        @JvmStatic fun of(profileId: String, revision: String, digest: String): WorkflowEgressProfileRef =
            WorkflowEgressProfileRef(profileId, revision, digest)
    }
}

/** Expiring indirection understood by a trusted secret broker. It is never the secret value. */
class WorkflowSecretHandle private constructor(
    handleId: String,
    purpose: String,
    version: String,
    digest: String,
    val expiresAtEpochMilli: Long,
) {
    val handleId: String = WorkflowSpiContractSupport.requireOpaqueReference(handleId, "Workflow secret handle is invalid.")
    val purpose: String = WorkflowSpiContractSupport.requireMachineCode(purpose, "Workflow secret purpose is invalid.")
    val version: String = WorkflowSpiContractSupport.requireText(
        version, WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES, "Workflow secret handle version is invalid.",
    )
    val digest: String = WorkflowSpiContractSupport.requireCanonicalSha256(digest, "Workflow secret handle digest is invalid.")

    init {
        require(expiresAtEpochMilli >= 0L) { "Workflow secret handle expiry is invalid." }
    }

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowSecretHandle && handleId == other.handleId && purpose == other.purpose &&
        version == other.version && digest == other.digest && expiresAtEpochMilli == other.expiresAtEpochMilli

    override fun hashCode(): Int {
        var result = handleId.hashCode()
        result = 31 * result + purpose.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + digest.hashCode()
        result = 31 * result + expiresAtEpochMilli.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowSecretHandle(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            handleId: String,
            purpose: String,
            version: String,
            digest: String,
            expiresAtEpochMilli: Long,
        ): WorkflowSecretHandle = WorkflowSecretHandle(handleId, purpose, version, digest, expiresAtEpochMilli)
    }
}

class WorkflowServiceTaskDescriptor private constructor(
    providerId: String,
    providerRevision: String,
    handlerId: String,
    handlerVersion: String,
    taskType: String,
    val inputSchema: WorkflowSchemaRef,
    val outputSchema: WorkflowSchemaRef,
    val timeoutMillis: Long,
    val retryPolicy: WorkflowServiceTaskRetryPolicy,
    val idempotencyMode: WorkflowServiceTaskIdempotencyMode,
    val compensation: WorkflowServiceTaskCompensationDescriptor?,
    allowedEgressProfiles: Collection<WorkflowEgressProfileRef>,
    requiredSecretPurposes: Collection<String>,
) {
    val providerId: String = WorkflowSpiContractSupport.requireMachineCode(providerId, "Workflow service-task provider is invalid.")
    val providerRevision: String = WorkflowSpiContractSupport.requireText(
        providerRevision, WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES, "Workflow service-task provider revision is invalid.",
    )
    val handlerId: String = WorkflowSpiContractSupport.requireMachineCode(handlerId, "Workflow service-task handler is invalid.")
    val handlerVersion: String = WorkflowSpiContractSupport.requireText(
        handlerVersion, WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES, "Workflow service-task handler version is invalid.",
    )
    val taskType: String = WorkflowSpiContractSupport.requireMachineCode(taskType, "Workflow service-task type is invalid.")
    val allowedEgressProfiles: List<WorkflowEgressProfileRef> = WorkflowSpiContractSupport.immutableList(
        allowedEgressProfiles, WorkflowSpiContractSupport.MAX_ITEMS, "Workflow egress profiles exceed the limit.",
    )
    val requiredSecretPurposes: List<String> = WorkflowSpiContractSupport.immutableList(
        requiredSecretPurposes.map {
            WorkflowSpiContractSupport.requireMachineCode(it, "Workflow required secret purpose is invalid.")
        },
        WorkflowSpiContractSupport.MAX_ITEMS,
        "Workflow required secret purposes exceed the limit.",
    )
    val descriptorDigest: String

    init {
        require(timeoutMillis in 1L..WorkflowSpiContractSupport.MAX_CALL_WINDOW_MILLIS) {
            "Workflow service-task timeout is invalid."
        }
        require(idempotencyMode == WorkflowServiceTaskIdempotencyMode.REQUIRED ||
            idempotencyMode == WorkflowServiceTaskIdempotencyMode.PROVIDER_GUARANTEED
        ) { "Unknown workflow service-task idempotency modes require future typed support." }
        require(this.allowedEgressProfiles.toSet().size == this.allowedEgressProfiles.size) {
            "Workflow egress profiles must be unique."
        }
        require(this.requiredSecretPurposes.toSet().size == this.requiredSecretPurposes.size) {
            "Workflow required secret purposes must be unique."
        }
        descriptorDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-service-descriptor-v1")
            .text(this.providerId)
            .text(this.providerRevision)
            .text(this.handlerId)
            .text(this.handlerVersion)
            .text(this.taskType)
            .text(inputSchema.providerId).text(inputSchema.schemaId).text(inputSchema.version).text(inputSchema.digest)
            .text(outputSchema.providerId).text(outputSchema.schemaId).text(outputSchema.version).text(outputSchema.digest)
            .longValue(timeoutMillis)
            .text(retryPolicy.policyDigest)
            .text(idempotencyMode.code)
            .optionalText(compensation?.handlerId)
            .optionalText(compensation?.version)
            .optionalText(compensation?.digest)
            .integer(this.allowedEgressProfiles.size)
            .also { writer -> this.allowedEgressProfiles.forEach { ref ->
                writer.text(ref.profileId).text(ref.revision).text(ref.digest)
            } }
            .integer(this.requiredSecretPurposes.size)
            .also { writer -> this.requiredSecretPurposes.forEach(writer::text) }
            .finish()
    }

    override fun toString(): String = "WorkflowServiceTaskDescriptor(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            providerId: String,
            providerRevision: String,
            handlerId: String,
            handlerVersion: String,
            taskType: String,
            inputSchema: WorkflowSchemaRef,
            outputSchema: WorkflowSchemaRef,
            timeoutMillis: Long,
            retryPolicy: WorkflowServiceTaskRetryPolicy,
            idempotencyMode: WorkflowServiceTaskIdempotencyMode,
            compensation: WorkflowServiceTaskCompensationDescriptor?,
            allowedEgressProfiles: Collection<WorkflowEgressProfileRef>,
            requiredSecretPurposes: Collection<String>,
        ): WorkflowServiceTaskDescriptor = WorkflowServiceTaskDescriptor(
            providerId, providerRevision, handlerId, handlerVersion, taskType, inputSchema, outputSchema,
            timeoutMillis, retryPolicy, idempotencyMode, compensation, allowedEgressProfiles, requiredSecretPurposes,
        )
    }
}

class WorkflowServiceTaskRequest private constructor(
    val context: WorkflowProviderCallContext,
    val descriptor: WorkflowServiceTaskDescriptor,
    val definition: WorkflowDefinitionRef,
    val instance: WorkflowInstanceRef,
    val subject: WorkflowSubjectSnapshot,
    val initiator: WorkflowPrincipalRef?,
    attemptId: String,
    idempotencyKey: String,
    val attemptNumber: Int,
    val input: WorkflowStructuredPayload,
    egressProfiles: Collection<WorkflowEgressProfileRef>,
    secretHandles: Collection<WorkflowSecretHandle>,
) {
    val attemptId: String = WorkflowSpiContractSupport.requireText(
        attemptId, WorkflowSpiContractSupport.MAX_ID_UTF8_BYTES, "Workflow service-task attempt identifier is invalid.",
    )
    val idempotencyKey: String = WorkflowSpiContractSupport.requireText(
        idempotencyKey, WorkflowSpiContractSupport.MAX_ID_UTF8_BYTES, "Workflow service-task idempotency key is invalid.",
    )
    val egressProfiles: List<WorkflowEgressProfileRef> = WorkflowSpiContractSupport.immutableList(
        egressProfiles, WorkflowSpiContractSupport.MAX_ITEMS, "Workflow service-task egress profiles exceed the limit.",
    )
    val secretHandles: List<WorkflowSecretHandle> = WorkflowSpiContractSupport.immutableList(
        secretHandles, WorkflowSpiContractSupport.MAX_ITEMS, "Workflow service-task secret handles exceed the limit.",
    )
    val requestDigest: String

    init {
        require(context.providerId == descriptor.providerId && context.providerRevision == descriptor.providerRevision) {
            "Workflow service-task descriptor does not match the provider context."
        }
        require(context.deadlineEpochMilli - context.requestedAtEpochMilli <= descriptor.timeoutMillis) {
            "Workflow service-task call window exceeds the descriptor timeout."
        }
        require(attemptNumber in 1..descriptor.retryPolicy.maximumAttempts) { "Workflow service-task attempt is invalid." }
        require(input.validated && input.schema == descriptor.inputSchema && input.size <= context.maximumInputBytes) {
            "Workflow service-task input does not match the descriptor or call limit."
        }
        require(this.egressProfiles.toSet().size == this.egressProfiles.size &&
            descriptor.allowedEgressProfiles.containsAll(this.egressProfiles)
        ) { "Workflow service-task egress profiles are not authorized by the descriptor." }
        require(this.secretHandles.map { it.purpose }.toSet().size == this.secretHandles.size &&
            this.secretHandles.map { it.purpose }.toSet() == descriptor.requiredSecretPurposes.toSet()
        ) { "Workflow service-task secret handles do not exactly satisfy the descriptor." }
        require(this.secretHandles.all { it.expiresAtEpochMilli >= context.deadlineEpochMilli }) {
            "Workflow service-task secret handles expire before the call deadline."
        }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-service-request-v1")
            .text(context.contextDigest)
            .text(descriptor.descriptorDigest)
            .text(definition.key).text(definition.version).text(definition.digest)
            .text(instance.id).longValue(instance.expectedVersion)
            .text(subject.ref.type).text(subject.ref.id).text(subject.revision).text(subject.digest)
            .optionalText(initiator?.type).optionalText(initiator?.id)
            .text(this.attemptId).text(this.idempotencyKey).integer(attemptNumber)
            .text(input.contentDigest)
            .integer(this.egressProfiles.size)
            .also { writer -> this.egressProfiles.forEach { ref ->
                writer.text(ref.profileId).text(ref.revision).text(ref.digest)
            } }
            .integer(this.secretHandles.size)
            .also { writer -> this.secretHandles.forEach { handle ->
                writer.text(handle.handleId).text(handle.purpose).text(handle.version).text(handle.digest)
                    .longValue(handle.expiresAtEpochMilli)
            } }
            .finish()
    }

    override fun toString(): String = "WorkflowServiceTaskRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowProviderCallContext,
            descriptor: WorkflowServiceTaskDescriptor,
            definition: WorkflowDefinitionRef,
            instance: WorkflowInstanceRef,
            subject: WorkflowSubjectSnapshot,
            initiator: WorkflowPrincipalRef?,
            attemptId: String,
            idempotencyKey: String,
            attemptNumber: Int,
            input: WorkflowStructuredPayload,
            egressProfiles: Collection<WorkflowEgressProfileRef>,
            secretHandles: Collection<WorkflowSecretHandle>,
        ): WorkflowServiceTaskRequest = WorkflowServiceTaskRequest(
            context, descriptor, definition, instance, subject, initiator, attemptId, idempotencyKey,
            attemptNumber, input, egressProfiles, secretHandles,
        )
    }
}

class WorkflowServiceTaskStatus private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(code, "Workflow service-task status is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowServiceTaskStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowServiceTaskStatus(<redacted>)"

    companion object {
        @JvmField val SUCCEEDED = WorkflowServiceTaskStatus("succeeded")
        @JvmField val RETRYABLE_FAILURE = WorkflowServiceTaskStatus("retryable-failure")
        @JvmField val PERMANENT_FAILURE = WorkflowServiceTaskStatus("permanent-failure")
        @JvmField val OUTCOME_UNKNOWN = WorkflowServiceTaskStatus("outcome-unknown")
        @JvmStatic fun of(code: String): WorkflowServiceTaskStatus = when (code) {
            SUCCEEDED.code -> SUCCEEDED
            RETRYABLE_FAILURE.code -> RETRYABLE_FAILURE
            PERMANENT_FAILURE.code -> PERMANENT_FAILURE
            OUTCOME_UNKNOWN.code -> OUTCOME_UNKNOWN
            else -> WorkflowServiceTaskStatus(code)
        }
    }
}

class WorkflowServiceTaskResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val status: WorkflowServiceTaskStatus,
    val output: WorkflowStructuredPayload?,
    externalReceiptRef: String?,
    externalReceiptDigest: String?,
) {
    val externalReceiptRef: String? = externalReceiptRef?.let {
        WorkflowSpiContractSupport.requireOpaqueReference(it, "Workflow service-task external receipt reference is invalid.")
    }
    val externalReceiptDigest: String? = externalReceiptDigest?.let {
        WorkflowSpiContractSupport.requireCanonicalSha256(it, "Workflow service-task external receipt digest is invalid.")
    }

    init {
        require((this.externalReceiptRef == null) == (this.externalReceiptDigest == null)) {
            "Workflow service-task external receipt reference and digest must be provided together."
        }
        if (status == WorkflowServiceTaskStatus.SUCCEEDED) {
            require(receipt.outcome == WorkflowProviderOutcome.SUCCESS && output != null && output.validated) {
                "Successful workflow service-task results require output."
            }
        } else {
            require(status == WorkflowServiceTaskStatus.RETRYABLE_FAILURE ||
                status == WorkflowServiceTaskStatus.PERMANENT_FAILURE || status == WorkflowServiceTaskStatus.OUTCOME_UNKNOWN
            ) { "Unknown workflow service-task statuses require future typed support." }
            require(receipt.outcome != WorkflowProviderOutcome.SUCCESS && output == null) {
                "Failed workflow service-task results cannot carry successful output."
            }
            when (status) {
                WorkflowServiceTaskStatus.RETRYABLE_FAILURE -> require(receipt.failure?.retryable == true) {
                    "Retryable workflow service-task failures require retryable failure metadata."
                }
                WorkflowServiceTaskStatus.PERMANENT_FAILURE,
                WorkflowServiceTaskStatus.OUTCOME_UNKNOWN -> require(receipt.failure?.retryable == false) {
                    "Terminal or unknown workflow service-task failures cannot request an automatic retry."
                }
            }
        }
    }

    override fun toString(): String = "WorkflowServiceTaskResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowServiceTaskRequest,
            output: WorkflowStructuredPayload,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowServiceTaskResult = success(
            request, output, null, null, completedAtEpochMilli, expiresAtEpochMilli,
        )

        @JvmStatic
        fun success(
            request: WorkflowServiceTaskRequest,
            output: WorkflowStructuredPayload,
            externalReceiptRef: String?,
            externalReceiptDigest: String?,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowServiceTaskResult {
            require(output.validated && output.schema == request.descriptor.outputSchema &&
                output.size <= request.context.maximumOutputBytes
            ) {
                "Workflow service-task output does not match the descriptor or call limit."
            }
            val ref = externalReceiptRef?.let { WorkflowSpiContractSupport.requireOpaqueReference(
                it, "Workflow service-task external receipt reference is invalid.",
            ) }
            val digest = externalReceiptDigest?.let { WorkflowSpiContractSupport.requireCanonicalSha256(
                it, "Workflow service-task external receipt digest is invalid.",
            ) }
            require((ref == null) == (digest == null)) { "Workflow service-task external receipt evidence is incomplete." }
            require(request.descriptor.compensation == null || ref != null) {
                "Compensatable workflow service-task success requires external receipt evidence."
            }
            val resultDigest = resultDigest(WorkflowServiceTaskStatus.SUCCEEDED, output, ref, digest, null)
            return WorkflowServiceTaskResult(
                WorkflowProviderReceipt.success(
                    request.context, request.requestDigest, resultDigest, completedAtEpochMilli, expiresAtEpochMilli,
                ),
                WorkflowServiceTaskStatus.SUCCEEDED,
                output,
                ref,
                digest,
            )
        }

        @JvmStatic
        fun failure(
            request: WorkflowServiceTaskRequest,
            status: WorkflowServiceTaskStatus,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            externalReceiptRef: String?,
            externalReceiptDigest: String?,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowServiceTaskResult {
            require(status != WorkflowServiceTaskStatus.SUCCEEDED) { "Failure result cannot use the succeeded status." }
            requireFailureConsistency(request, status, failure)
            val ref = externalReceiptRef?.let {
                WorkflowSpiContractSupport.requireOpaqueReference(it, "Workflow service-task external receipt reference is invalid.")
            }
            val digest = externalReceiptDigest?.let {
                WorkflowSpiContractSupport.requireCanonicalSha256(it, "Workflow service-task external receipt digest is invalid.")
            }
            require((ref == null) == (digest == null)) { "Workflow service-task external receipt evidence is incomplete." }
            val resultDigest = resultDigest(status, null, ref, digest, failure)
            return WorkflowServiceTaskResult(
                WorkflowProviderReceipt.failure(
                    request.context, request.requestDigest, outcome, resultDigest, failure,
                    completedAtEpochMilli, expiresAtEpochMilli,
                ),
                status,
                null,
                ref,
                digest,
            )
        }

        private fun requireFailureConsistency(
            request: WorkflowServiceTaskRequest,
            status: WorkflowServiceTaskStatus,
            failure: WorkflowProviderFailure,
        ) {
            val retryCodes = request.descriptor.retryPolicy.retryableFailureCodes
            when (status) {
                WorkflowServiceTaskStatus.RETRYABLE_FAILURE -> {
                    require(failure.retryable && failure.code in retryCodes) {
                        "Retryable workflow service-task status requires a declared retryable failure code."
                    }
                    require(request.attemptNumber < request.descriptor.retryPolicy.maximumAttempts) {
                        "Retryable workflow service-task status requires a remaining retry attempt."
                    }
                }
                WorkflowServiceTaskStatus.PERMANENT_FAILURE -> require(!failure.retryable && failure.code !in retryCodes) {
                    "Permanent workflow service-task status contradicts its retry metadata."
                }
                WorkflowServiceTaskStatus.OUTCOME_UNKNOWN -> require(!failure.retryable && failure.code !in retryCodes) {
                    "Unknown workflow service-task outcomes are reconciliation-only and cannot be retryable."
                }
                else -> throw IllegalArgumentException("Unknown workflow service-task statuses require future typed support.")
            }
        }

        private fun resultDigest(
            status: WorkflowServiceTaskStatus,
            output: WorkflowStructuredPayload?,
            externalReceiptRef: String?,
            externalReceiptDigest: String?,
            failure: WorkflowProviderFailure?,
        ): String = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-service-result-v1")
            .text(status.code)
            .optionalText(output?.contentDigest)
            .optionalText(externalReceiptRef)
            .optionalText(externalReceiptDigest)
            .optionalText(failure?.code)
            .booleanValue(failure?.retryable ?: false)
            .finish()
    }
}

class WorkflowServiceTaskCompensationRequest private constructor(
    val context: WorkflowProviderCallContext,
    val descriptor: WorkflowServiceTaskDescriptor,
    val instance: WorkflowInstanceRef,
    originalRequestDigest: String,
    originalReceiptDigest: String,
    idempotencyKey: String,
    externalReceiptRef: String,
    externalReceiptDigest: String,
    egressProfiles: Collection<WorkflowEgressProfileRef>,
    secretHandles: Collection<WorkflowSecretHandle>,
) {
    val originalRequestDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        originalRequestDigest, "Original workflow service-task request digest is invalid.",
    )
    val originalReceiptDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        originalReceiptDigest, "Original workflow service-task receipt digest is invalid.",
    )
    val idempotencyKey: String = WorkflowSpiContractSupport.requireText(
        idempotencyKey, WorkflowSpiContractSupport.MAX_ID_UTF8_BYTES, "Workflow compensation idempotency key is invalid.",
    )
    val externalReceiptRef: String = WorkflowSpiContractSupport.requireOpaqueReference(
        externalReceiptRef, "Workflow compensation external receipt reference is invalid.",
    )
    val externalReceiptDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        externalReceiptDigest, "Workflow compensation external receipt digest is invalid.",
    )
    val egressProfiles: List<WorkflowEgressProfileRef> = WorkflowSpiContractSupport.immutableList(
        egressProfiles, WorkflowSpiContractSupport.MAX_ITEMS, "Workflow compensation egress profiles exceed the limit.",
    )
    val secretHandles: List<WorkflowSecretHandle> = WorkflowSpiContractSupport.immutableList(
        secretHandles, WorkflowSpiContractSupport.MAX_ITEMS, "Workflow compensation secret handles exceed the limit.",
    )
    val requestDigest: String

    init {
        require(context.providerId == descriptor.providerId && context.providerRevision == descriptor.providerRevision) {
            "Workflow compensation descriptor does not match the provider context."
        }
        require(descriptor.compensation != null) { "Workflow service-task descriptor does not support compensation." }
        require(this.egressProfiles.toSet().size == this.egressProfiles.size &&
            descriptor.allowedEgressProfiles.containsAll(this.egressProfiles)
        ) { "Workflow compensation egress profiles are not authorized by the descriptor." }
        require(this.secretHandles.map { it.purpose }.toSet().size == this.secretHandles.size &&
            this.secretHandles.map { it.purpose }.toSet() == descriptor.requiredSecretPurposes.toSet()
        ) { "Workflow compensation secret handles do not exactly satisfy the descriptor." }
        require(this.secretHandles.all { it.expiresAtEpochMilli >= context.deadlineEpochMilli }) {
            "Workflow compensation secret handles expire before the call deadline."
        }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-service-compensation-request-v1")
            .text(context.contextDigest)
            .text(descriptor.descriptorDigest)
            .text(instance.id).longValue(instance.expectedVersion)
            .text(this.originalRequestDigest).text(this.originalReceiptDigest).text(this.idempotencyKey)
            .text(this.externalReceiptRef).text(this.externalReceiptDigest)
            .integer(this.egressProfiles.size)
            .also { writer -> this.egressProfiles.forEach { ref ->
                writer.text(ref.profileId).text(ref.revision).text(ref.digest)
            } }
            .integer(this.secretHandles.size)
            .also { writer -> this.secretHandles.forEach { handle ->
                writer.text(handle.handleId).text(handle.purpose).text(handle.version).text(handle.digest)
                    .longValue(handle.expiresAtEpochMilli)
            } }
            .finish()
    }

    override fun toString(): String = "WorkflowServiceTaskCompensationRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowProviderCallContext,
            descriptor: WorkflowServiceTaskDescriptor,
            instance: WorkflowInstanceRef,
            originalRequestDigest: String,
            originalReceiptDigest: String,
            idempotencyKey: String,
            externalReceiptRef: String,
            externalReceiptDigest: String,
            egressProfiles: Collection<WorkflowEgressProfileRef>,
            secretHandles: Collection<WorkflowSecretHandle>,
        ): WorkflowServiceTaskCompensationRequest = WorkflowServiceTaskCompensationRequest(
            context, descriptor, instance, originalRequestDigest, originalReceiptDigest,
            idempotencyKey, externalReceiptRef, externalReceiptDigest, egressProfiles, secretHandles,
        )
    }
}

class WorkflowCompensationStatus private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(code, "Workflow compensation status is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowCompensationStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowCompensationStatus(<redacted>)"

    companion object {
        @JvmField val COMPENSATED = WorkflowCompensationStatus("compensated")
        @JvmField val ALREADY_COMPENSATED = WorkflowCompensationStatus("already-compensated")
        @JvmField val RETRYABLE_FAILURE = WorkflowCompensationStatus("retryable-failure")
        @JvmField val PERMANENT_FAILURE = WorkflowCompensationStatus("permanent-failure")
        @JvmField val OUTCOME_UNKNOWN = WorkflowCompensationStatus("outcome-unknown")
        @JvmStatic fun of(code: String): WorkflowCompensationStatus = when (code) {
            COMPENSATED.code -> COMPENSATED
            ALREADY_COMPENSATED.code -> ALREADY_COMPENSATED
            RETRYABLE_FAILURE.code -> RETRYABLE_FAILURE
            PERMANENT_FAILURE.code -> PERMANENT_FAILURE
            OUTCOME_UNKNOWN.code -> OUTCOME_UNKNOWN
            else -> WorkflowCompensationStatus(code)
        }
    }
}

class WorkflowServiceTaskCompensationResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val status: WorkflowCompensationStatus,
) {
    init {
        val successful = status == WorkflowCompensationStatus.COMPENSATED ||
            status == WorkflowCompensationStatus.ALREADY_COMPENSATED
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == successful) {
            "Workflow compensation status does not match its provider outcome."
        }
        require(successful || status == WorkflowCompensationStatus.RETRYABLE_FAILURE ||
            status == WorkflowCompensationStatus.PERMANENT_FAILURE || status == WorkflowCompensationStatus.OUTCOME_UNKNOWN
        ) { "Unknown workflow compensation statuses require future typed support." }
        if (!successful) {
            when (status) {
                WorkflowCompensationStatus.RETRYABLE_FAILURE -> require(receipt.failure?.retryable == true) {
                    "Retryable workflow compensation failures require retryable failure metadata."
                }
                WorkflowCompensationStatus.PERMANENT_FAILURE,
                WorkflowCompensationStatus.OUTCOME_UNKNOWN -> require(receipt.failure?.retryable == false) {
                    "Terminal or unknown workflow compensation failures cannot request an automatic retry."
                }
            }
        }
    }

    override fun toString(): String = "WorkflowServiceTaskCompensationResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowServiceTaskCompensationRequest,
            status: WorkflowCompensationStatus,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowServiceTaskCompensationResult {
            require(status == WorkflowCompensationStatus.COMPENSATED ||
                status == WorkflowCompensationStatus.ALREADY_COMPENSATED
            ) { "Successful compensation requires a successful status." }
            val digest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-service-compensation-result-v1")
                .text(status.code).finish()
            return WorkflowServiceTaskCompensationResult(
                WorkflowProviderReceipt.success(
                    request.context, request.requestDigest, digest, completedAtEpochMilli, expiresAtEpochMilli,
                ),
                status,
            )
        }

        @JvmStatic
        fun failure(
            request: WorkflowServiceTaskCompensationRequest,
            status: WorkflowCompensationStatus,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowServiceTaskCompensationResult {
            require(status != WorkflowCompensationStatus.COMPENSATED &&
                status != WorkflowCompensationStatus.ALREADY_COMPENSATED
            ) { "Failed compensation cannot use a successful status." }
            when (status) {
                WorkflowCompensationStatus.RETRYABLE_FAILURE -> require(failure.retryable) {
                    "Retryable workflow compensation status requires retryable failure metadata."
                }
                WorkflowCompensationStatus.PERMANENT_FAILURE -> require(!failure.retryable) {
                    "Permanent workflow compensation status contradicts its retry metadata."
                }
                WorkflowCompensationStatus.OUTCOME_UNKNOWN -> require(!failure.retryable) {
                    "Unknown workflow compensation outcomes are reconciliation-only and cannot be retryable."
                }
                else -> throw IllegalArgumentException("Unknown workflow compensation statuses require future typed support.")
            }
            val digest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-service-compensation-result-v1")
                .text(status.code).text(failure.code).booleanValue(failure.retryable).finish()
            return WorkflowServiceTaskCompensationResult(
                WorkflowProviderReceipt.failure(
                    request.context, request.requestDigest, outcome, digest, failure,
                    completedAtEpochMilli, expiresAtEpochMilli,
                ),
                status,
            )
        }
    }
}

interface WorkflowServiceTaskHandler {
    /** Pure metadata lookup; it must not perform I/O or inspect tenant state. */
    fun descriptor(): WorkflowServiceTaskDescriptor

    fun execute(request: WorkflowServiceTaskRequest): CompletionStage<WorkflowServiceTaskResult>
}

fun interface WorkflowServiceTaskCompensationHandler {
    fun compensate(request: WorkflowServiceTaskCompensationRequest): CompletionStage<WorkflowServiceTaskCompensationResult>
}
