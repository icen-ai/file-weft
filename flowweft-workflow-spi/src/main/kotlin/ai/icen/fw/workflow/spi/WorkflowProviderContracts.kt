package ai.icen.fw.workflow.spi

/** Exact, bounded invocation context created by the trusted Workflow runtime. */
class WorkflowProviderCallContext private constructor(
    requestId: String,
    tenantId: String,
    providerId: String,
    providerRevision: String,
    purpose: String,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
    val maximumInputBytes: Int,
    val maximumOutputBytes: Int,
    val maximumItems: Int,
) {
    val requestId: String = WorkflowSpiContractSupport.requireText(
        requestId, WorkflowSpiContractSupport.MAX_ID_UTF8_BYTES, "Workflow provider request identifier is invalid.",
    )
    val tenantId: String = WorkflowSpiContractSupport.requireText(
        tenantId, WorkflowSpiContractSupport.MAX_ID_UTF8_BYTES, "Workflow provider tenant identifier is invalid.",
    )
    val providerId: String = WorkflowSpiContractSupport.requireMachineCode(
        providerId, "Workflow provider identifier is invalid.",
    )
    val providerRevision: String = WorkflowSpiContractSupport.requireText(
        providerRevision,
        WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES,
        "Workflow provider revision is invalid.",
    )
    val purpose: String = WorkflowSpiContractSupport.requireMachineCode(
        purpose, "Workflow provider purpose is invalid.",
    )
    val contextDigest: String

    init {
        require(requestedAtEpochMilli >= 0L && deadlineEpochMilli > requestedAtEpochMilli) {
            "Workflow provider deadline must follow its request time."
        }
        require(deadlineEpochMilli - requestedAtEpochMilli <= WorkflowSpiContractSupport.MAX_CALL_WINDOW_MILLIS) {
            "Workflow provider call window exceeds the limit."
        }
        require(maximumInputBytes in 1..WorkflowSpiContractSupport.MAX_PAYLOAD_BYTES) {
            "Workflow provider input byte limit is invalid."
        }
        require(maximumOutputBytes in 1..WorkflowSpiContractSupport.MAX_PAYLOAD_BYTES) {
            "Workflow provider output byte limit is invalid."
        }
        require(maximumItems in 1..WorkflowSpiContractSupport.MAX_ITEMS) {
            "Workflow provider item limit is invalid."
        }
        contextDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-call-context-v1")
            .text(this.requestId)
            .text(this.tenantId)
            .text(this.providerId)
            .text(this.providerRevision)
            .text(this.purpose)
            .longValue(requestedAtEpochMilli)
            .longValue(deadlineEpochMilli)
            .integer(maximumInputBytes)
            .integer(maximumOutputBytes)
            .integer(maximumItems)
            .finish()
    }

    override fun toString(): String = "WorkflowProviderCallContext(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            requestId: String,
            tenantId: String,
            providerId: String,
            providerRevision: String,
            purpose: String,
            requestedAtEpochMilli: Long,
            deadlineEpochMilli: Long,
            maximumInputBytes: Int,
            maximumOutputBytes: Int,
            maximumItems: Int,
        ): WorkflowProviderCallContext = WorkflowProviderCallContext(
            requestId,
            tenantId,
            providerId,
            providerRevision,
            purpose,
            requestedAtEpochMilli,
            deadlineEpochMilli,
            maximumInputBytes,
            maximumOutputBytes,
            maximumItems,
        )
    }
}

/** Extensible provider outcome. Anything except [SUCCESS] remains fail-closed. */
class WorkflowProviderOutcome private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(code, "Workflow provider outcome is invalid.")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowProviderOutcome && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowProviderOutcome(<redacted>)"

    companion object {
        @JvmField val SUCCESS = WorkflowProviderOutcome("success")
        @JvmField val NOT_FOUND = WorkflowProviderOutcome("not-found")
        @JvmField val DENIED = WorkflowProviderOutcome("denied")
        @JvmField val UNSUPPORTED = WorkflowProviderOutcome("unsupported")
        @JvmField val INVALID = WorkflowProviderOutcome("invalid")
        @JvmField val UNAVAILABLE = WorkflowProviderOutcome("unavailable")
        @JvmField val FAILED = WorkflowProviderOutcome("failed")

        @JvmStatic
        fun of(code: String): WorkflowProviderOutcome = when (code) {
            SUCCESS.code -> SUCCESS
            NOT_FOUND.code -> NOT_FOUND
            DENIED.code -> DENIED
            UNSUPPORTED.code -> UNSUPPORTED
            INVALID.code -> INVALID
            UNAVAILABLE.code -> UNAVAILABLE
            FAILED.code -> FAILED
            else -> WorkflowProviderOutcome(code)
        }
    }
}

/** Value-free failure classification. Vendor exception text must never be stored here. */
class WorkflowProviderFailure private constructor(code: String, val retryable: Boolean) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(code, "Workflow provider failure code is invalid.")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowProviderFailure && code == other.code && retryable == other.retryable

    override fun hashCode(): Int = 31 * code.hashCode() + retryable.hashCode()
    override fun toString(): String = "WorkflowProviderFailure(<redacted>)"

    companion object {
        @JvmStatic
        fun of(code: String, retryable: Boolean): WorkflowProviderFailure =
            WorkflowProviderFailure(code, retryable)
    }
}

/** Invocation-bound receipt. It is consistency evidence, never authorization or a bearer token. */
class WorkflowProviderReceipt private constructor(
    context: WorkflowProviderCallContext,
    requestDigest: String,
    val outcome: WorkflowProviderOutcome,
    resultDigest: String,
    val failure: WorkflowProviderFailure?,
    val completedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val requestId: String = context.requestId
    val tenantId: String = context.tenantId
    val providerId: String = context.providerId
    val providerRevision: String = context.providerRevision
    val contextDigest: String = context.contextDigest
    val requestDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        requestDigest, "Workflow provider request digest is invalid.",
    )
    val resultDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        resultDigest, "Workflow provider result digest is invalid.",
    )
    val receiptDigest: String

    init {
        require(completedAtEpochMilli in context.requestedAtEpochMilli..context.deadlineEpochMilli) {
            "Workflow provider completion time is outside its request window."
        }
        require(expiresAtEpochMilli in completedAtEpochMilli..context.deadlineEpochMilli) {
            "Workflow provider receipt expiry is outside its request window."
        }
        if (outcome == WorkflowProviderOutcome.SUCCESS) {
            require(failure == null) { "Successful workflow provider receipts cannot carry failure metadata." }
        } else {
            require(failure != null) { "Unsuccessful workflow provider receipts require failure metadata." }
        }
        receiptDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-provider-receipt-v1")
            .text(contextDigest)
            .text(this.requestDigest)
            .text(outcome.code)
            .text(this.resultDigest)
            .optionalText(failure?.code)
            .booleanValue(failure?.retryable ?: false)
            .longValue(completedAtEpochMilli)
            .longValue(expiresAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "WorkflowProviderReceipt(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            context: WorkflowProviderCallContext,
            requestDigest: String,
            resultDigest: String,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowProviderReceipt = WorkflowProviderReceipt(
            context,
            requestDigest,
            WorkflowProviderOutcome.SUCCESS,
            resultDigest,
            null,
            completedAtEpochMilli,
            expiresAtEpochMilli,
        )

        @JvmStatic
        fun failure(
            context: WorkflowProviderCallContext,
            requestDigest: String,
            outcome: WorkflowProviderOutcome,
            resultDigest: String,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowProviderReceipt {
            require(outcome != WorkflowProviderOutcome.SUCCESS) {
                "Failure workflow provider receipts cannot use the success outcome."
            }
            return WorkflowProviderReceipt(
                context,
                requestDigest,
                outcome,
                resultDigest,
                failure,
                completedAtEpochMilli,
                expiresAtEpochMilli,
            )
        }
    }
}

/** Versioned schema identity; identifiers are registry keys, never URLs or executable locations. */
class WorkflowSchemaRef private constructor(
    providerId: String,
    schemaId: String,
    version: String,
    digest: String,
) {
    val providerId: String = WorkflowSpiContractSupport.requireMachineCode(providerId, "Workflow schema provider is invalid.")
    val schemaId: String = WorkflowSpiContractSupport.requireMachineCode(schemaId, "Workflow schema identifier is invalid.")
    val version: String = WorkflowSpiContractSupport.requireText(
        version, WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES, "Workflow schema version is invalid.",
    )
    val digest: String = WorkflowSpiContractSupport.requireCanonicalSha256(digest, "Workflow schema digest is invalid.")

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowSchemaRef && providerId == other.providerId && schemaId == other.schemaId &&
        version == other.version && digest == other.digest

    override fun hashCode(): Int {
        var result = providerId.hashCode()
        result = 31 * result + schemaId.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + digest.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowSchemaRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(providerId: String, schemaId: String, version: String, digest: String): WorkflowSchemaRef =
            WorkflowSchemaRef(providerId, schemaId, version, digest)
    }
}

/**
 * Evidence that an exact canonical payload passed an exact schema-validator revision.
 *
 * This is a consistency receipt, not a bearer token. The trusted runtime remains responsible for
 * accepting receipts only from its configured validator invocation and authority evidence.
 */
class WorkflowPayloadValidationReceipt private constructor(
    validatorId: String,
    validatorRevision: String,
    val schema: WorkflowSchemaRef,
    canonicalPayloadDigest: String,
    val fieldCount: Int,
    authorityReceiptDigest: String,
) {
    val validatorId: String = WorkflowSpiContractSupport.requireMachineCode(
        validatorId, "Workflow payload validator identifier is invalid.",
    )
    val validatorRevision: String = WorkflowSpiContractSupport.requireText(
        validatorRevision,
        WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES,
        "Workflow payload validator revision is invalid.",
    )
    val canonicalPayloadDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        canonicalPayloadDigest, "Workflow validated payload digest is invalid.",
    )
    val authorityReceiptDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        authorityReceiptDigest, "Workflow payload validation authority receipt digest is invalid.",
    )
    val receiptDigest: String

    init {
        require(fieldCount in 0..WorkflowSpiContractSupport.MAX_ITEMS) {
            "Workflow validated payload field count is invalid."
        }
        receiptDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-payload-validation-receipt-v1")
            .text(this.validatorId)
            .text(this.validatorRevision)
            .text(schema.providerId)
            .text(schema.schemaId)
            .text(schema.version)
            .text(schema.digest)
            .text(this.canonicalPayloadDigest)
            .integer(fieldCount)
            .text(this.authorityReceiptDigest)
            .finish()
    }

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowPayloadValidationReceipt && validatorId == other.validatorId &&
        validatorRevision == other.validatorRevision && schema == other.schema &&
        canonicalPayloadDigest == other.canonicalPayloadDigest && fieldCount == other.fieldCount &&
        authorityReceiptDigest == other.authorityReceiptDigest

    override fun hashCode(): Int {
        var result = validatorId.hashCode()
        result = 31 * result + validatorRevision.hashCode()
        result = 31 * result + schema.hashCode()
        result = 31 * result + canonicalPayloadDigest.hashCode()
        result = 31 * result + fieldCount
        result = 31 * result + authorityReceiptDigest.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowPayloadValidationReceipt(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            validatorId: String,
            validatorRevision: String,
            schema: WorkflowSchemaRef,
            canonicalPayloadDigest: String,
            fieldCount: Int,
            authorityReceiptDigest: String,
        ): WorkflowPayloadValidationReceipt = WorkflowPayloadValidationReceipt(
            validatorId,
            validatorRevision,
            schema,
            canonicalPayloadDigest,
            fieldCount,
            authorityReceiptDigest,
        )
    }
}

/**
 * Canonical JSON data. [of] creates raw, untrusted input; [validated] binds it to
 * exact schema-validator evidence before it may cross a trusted provider boundary.
 * Secret values are forbidden; use handles.
 */
class WorkflowStructuredPayload private constructor(
    val schema: WorkflowSchemaRef,
    canonicalJson: ByteArray,
    val validationReceipt: WorkflowPayloadValidationReceipt?,
) {
    private val content: ByteArray = WorkflowSpiContractSupport.immutableBytes(
        canonicalJson,
        WorkflowSpiContractSupport.MAX_PAYLOAD_BYTES,
        "Workflow structured payload is empty or exceeds the limit.",
    )
    val size: Int = content.size
    val canonicalPayloadDigest: String = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-structured-payload-v1")
        .text(schema.providerId)
        .text(schema.schemaId)
        .text(schema.version)
        .text(schema.digest)
        .bytes(content)
        .finish()
    val validated: Boolean = validationReceipt != null
    val fieldCount: Int? = validationReceipt?.fieldCount
    val contentDigest: String

    init {
        validationReceipt?.let { receipt ->
            require(receipt.schema == schema && receipt.canonicalPayloadDigest == canonicalPayloadDigest) {
                "Workflow payload validation receipt does not match the exact schema and canonical content."
            }
        }
        contentDigest = validationReceipt?.let { receipt ->
            WorkflowSpiContractSupport.digest("flowweft-workflow-spi-validated-structured-payload-v1")
                .text(canonicalPayloadDigest)
                .text(receipt.receiptDigest)
                .finish()
        } ?: canonicalPayloadDigest
    }

    fun bytes(): ByteArray = content.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowStructuredPayload && schema == other.schema &&
        content.contentEquals(other.content) && validationReceipt == other.validationReceipt

    override fun hashCode(): Int = 31 * (31 * schema.hashCode() + content.contentHashCode()) +
        (validationReceipt?.hashCode() ?: 0)
    override fun toString(): String = "WorkflowStructuredPayload(<redacted>)"

    companion object {
        const val MEDIA_TYPE: String = "application/json"

        @JvmStatic
        fun of(schema: WorkflowSchemaRef, canonicalJson: ByteArray): WorkflowStructuredPayload =
            WorkflowStructuredPayload(schema, canonicalJson, null)

        @JvmStatic
        fun validated(
            schema: WorkflowSchemaRef,
            canonicalJson: ByteArray,
            validationReceipt: WorkflowPayloadValidationReceipt,
        ): WorkflowStructuredPayload = WorkflowStructuredPayload(schema, canonicalJson, validationReceipt)

        @JvmStatic
        fun validated(
            rawPayload: WorkflowStructuredPayload,
            validationReceipt: WorkflowPayloadValidationReceipt,
        ): WorkflowStructuredPayload {
            require(!rawPayload.validated) { "A validated workflow payload cannot be validated again." }
            return WorkflowStructuredPayload(rawPayload.schema, rawPayload.content, validationReceipt)
        }
    }
}
