package ai.icen.fw.workflow.api

/** A schema dialect identifier. Unknown dialects never become executable implicitly. */
class WorkflowJsonSchemaDialect private constructor(code: String) {
    val code: String = WorkflowContractSupport.requireMachineCode(
        code,
        "Workflow JSON Schema dialect is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowJsonSchemaDialect && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowJsonSchemaDialect(<redacted>)"

    companion object {
        /** The only executable FlowWeft 1.0 form-data dialect. */
        @JvmField
        val JSON_SCHEMA_2020_12 = WorkflowJsonSchemaDialect("json-schema-2020-12")

        @JvmStatic
        fun of(code: String): WorkflowJsonSchemaDialect = when (code) {
            JSON_SCHEMA_2020_12.code -> JSON_SCHEMA_2020_12
            else -> WorkflowJsonSchemaDialect(code)
        }
    }
}

/**
 * Immutable registry identity for JSON Schema content. Registry keys are not network locations;
 * resolving the bytes and choosing a validator remain host-owned SPI responsibilities.
 */
class WorkflowJsonSchemaRef private constructor(
    registryId: String,
    schemaId: String,
    version: String,
    val dialect: WorkflowJsonSchemaDialect,
    digest: String,
) {
    val registryId: String = WorkflowContractSupport.requireMachineCode(
        registryId,
        "Workflow schema registry identifier is invalid.",
    )
    val schemaId: String = WorkflowContractSupport.requireMachineCode(
        schemaId,
        "Workflow schema identifier is invalid.",
    )
    val version: String = WorkflowContractSupport.requireText(
        version,
        WorkflowContractSupport.MAX_DEFINITION_VERSION_UTF8_BYTES,
        "Workflow schema version is invalid.",
    )
    val digest: String = WorkflowContractSupport.requireCanonicalSha256(
        digest,
        "Workflow schema digest is invalid.",
    )

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowJsonSchemaRef && registryId == other.registryId &&
        schemaId == other.schemaId && version == other.version && dialect == other.dialect &&
        digest == other.digest

    override fun hashCode(): Int {
        var result = registryId.hashCode()
        result = 31 * result + schemaId.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + dialect.hashCode()
        result = 31 * result + digest.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowJsonSchemaRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            registryId: String,
            schemaId: String,
            version: String,
            dialect: WorkflowJsonSchemaDialect,
            digest: String,
        ): WorkflowJsonSchemaRef = WorkflowJsonSchemaRef(registryId, schemaId, version, dialect, digest)
    }
}

/** Exact, immutable form version. UI layout is a separately versioned opaque document. */
class WorkflowFormVersionRef private constructor(
    formKey: String,
    version: String,
    val dataSchema: WorkflowJsonSchemaRef,
    uiSchemaVersion: String?,
    uiSchemaDigest: String?,
    formDigest: String,
) {
    val formKey: String = WorkflowContractSupport.requireMachineCode(formKey, "Workflow form key is invalid.")
    val version: String = WorkflowContractSupport.requireText(
        version,
        WorkflowContractSupport.MAX_DEFINITION_VERSION_UTF8_BYTES,
        "Workflow form version is invalid.",
    )
    val uiSchemaVersion: String? = uiSchemaVersion?.let {
        WorkflowContractSupport.requireText(
            it,
            WorkflowContractSupport.MAX_DEFINITION_VERSION_UTF8_BYTES,
            "Workflow UI schema version is invalid.",
        )
    }
    val uiSchemaDigest: String? = uiSchemaDigest?.let {
        WorkflowContractSupport.requireCanonicalSha256(it, "Workflow UI schema digest is invalid.")
    }
    val formDigest: String = WorkflowContractSupport.requireCanonicalSha256(
        formDigest,
        "Workflow form digest is invalid.",
    )
    val bindingDigest: String

    init {
        require(dataSchema.dialect == WorkflowJsonSchemaDialect.JSON_SCHEMA_2020_12) {
            "FlowWeft 1.0 forms require JSON Schema 2020-12."
        }
        require((this.uiSchemaVersion == null) == (this.uiSchemaDigest == null)) {
            "Workflow UI schema version and digest must be supplied together."
        }
        bindingDigest = WorkflowContractSupport.digest("flowweft-workflow-form-version-ref-v1")
            .text(this.formKey)
            .text(this.version)
            .text(dataSchema.registryId)
            .text(dataSchema.schemaId)
            .text(dataSchema.version)
            .text(dataSchema.dialect.code)
            .text(dataSchema.digest)
            .optionalText(this.uiSchemaVersion)
            .optionalText(this.uiSchemaDigest)
            .text(this.formDigest)
            .finish()
    }

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowFormVersionRef && bindingDigest == other.bindingDigest

    override fun hashCode(): Int = bindingDigest.hashCode()
    override fun toString(): String = "WorkflowFormVersionRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            formKey: String,
            version: String,
            dataSchema: WorkflowJsonSchemaRef,
            uiSchemaVersion: String?,
            uiSchemaDigest: String?,
            formDigest: String,
        ): WorkflowFormVersionRef = WorkflowFormVersionRef(
            formKey,
            version,
            dataSchema,
            uiSchemaVersion,
            uiSchemaDigest,
            formDigest,
        )
    }
}

/** RFC 6901 JSON Pointer used only as a data-field address, never as executable input. */
class WorkflowFormFieldPath private constructor(value: String) {
    val value: String = WorkflowContractSupport.requireText(
        value,
        WorkflowContractSupport.MAX_DESCRIPTION_UTF8_BYTES,
        "Workflow form field path is invalid.",
    )

    init {
        require(this.value.startsWith("/")) { "Workflow form field path must be an absolute JSON Pointer." }
        var index = 0
        while (index < this.value.length) {
            if (this.value[index] == '~') {
                require(index + 1 < this.value.length &&
                    (this.value[index + 1] == '0' || this.value[index + 1] == '1')
                ) { "Workflow form field path contains an invalid JSON Pointer escape." }
                index += 2
            } else {
                index += 1
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowFormFieldPath && value == other.value

    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "WorkflowFormFieldPath(<redacted>)"

    companion object {
        @JvmStatic
        fun of(value: String): WorkflowFormFieldPath = WorkflowFormFieldPath(value)
    }
}

/** Closed field decision. Unknown modes must be interpreted as [DENY]. */
class WorkflowFormFieldAccessMode private constructor(code: String) {
    val code: String = WorkflowContractSupport.requireMachineCode(code, "Workflow field access mode is invalid.")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowFormFieldAccessMode && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowFormFieldAccessMode(<redacted>)"

    companion object {
        @JvmField val ALLOW = WorkflowFormFieldAccessMode("allow")
        @JvmField val REDACT = WorkflowFormFieldAccessMode("redact")
        @JvmField val DENY = WorkflowFormFieldAccessMode("deny")

        @JvmStatic
        fun of(code: String): WorkflowFormFieldAccessMode = when (code) {
            ALLOW.code -> ALLOW
            REDACT.code -> REDACT
            DENY.code -> DENY
            else -> WorkflowFormFieldAccessMode(code)
        }
    }
}

class WorkflowFormFieldAccessDecision private constructor(
    val path: WorkflowFormFieldPath,
    val readMode: WorkflowFormFieldAccessMode,
    val writeMode: WorkflowFormFieldAccessMode,
) {
    val decisionDigest: String

    init {
        require(readMode == WorkflowFormFieldAccessMode.ALLOW ||
            readMode == WorkflowFormFieldAccessMode.REDACT || readMode == WorkflowFormFieldAccessMode.DENY
        ) { "Unknown workflow read modes fail closed and cannot enter a trusted report." }
        require(writeMode == WorkflowFormFieldAccessMode.ALLOW || writeMode == WorkflowFormFieldAccessMode.DENY) {
            "Workflow write access must be explicitly allowed or denied."
        }
        decisionDigest = WorkflowContractSupport.digest("flowweft-workflow-form-field-access-v1")
            .text(path.value)
            .text(readMode.code)
            .text(writeMode.code)
            .finish()
    }

    override fun toString(): String = "WorkflowFormFieldAccessDecision(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            path: WorkflowFormFieldPath,
            readMode: WorkflowFormFieldAccessMode,
            writeMode: WorkflowFormFieldAccessMode,
        ): WorkflowFormFieldAccessDecision = WorkflowFormFieldAccessDecision(path, readMode, writeMode)

        @JvmStatic
        fun denied(path: WorkflowFormFieldPath): WorkflowFormFieldAccessDecision =
            WorkflowFormFieldAccessDecision(
                path,
                WorkflowFormFieldAccessMode.DENY,
                WorkflowFormFieldAccessMode.DENY,
            )
    }
}

/** Complete field-level decision set. Paths not present in this report are always denied. */
class WorkflowFormFieldAccessReport private constructor(
    decisions: Collection<WorkflowFormFieldAccessDecision>,
    authorityReceiptDigest: String,
) {
    val decisions: List<WorkflowFormFieldAccessDecision> = java.util.Collections.unmodifiableList(
        WorkflowContractSupport.immutableList(
            decisions,
            WorkflowContractSupport.MAX_PRINCIPALS,
            "Workflow form field decisions exceed the limit.",
        ).sortedBy { it.path.value },
    )
    val authorityReceiptDigest: String = WorkflowContractSupport.requireCanonicalSha256(
        authorityReceiptDigest,
        "Workflow field authorization receipt digest is invalid.",
    )
    val reportDigest: String

    init {
        require(this.decisions.map { it.path }.toSet().size == this.decisions.size) {
            "Workflow form field decisions must have unique paths."
        }
        reportDigest = WorkflowContractSupport.digest("flowweft-workflow-form-field-report-v1")
            .integer(this.decisions.size)
            .also { writer -> this.decisions.forEach { writer.text(it.decisionDigest) } }
            .text(this.authorityReceiptDigest)
            .finish()
    }

    fun readMode(path: WorkflowFormFieldPath): WorkflowFormFieldAccessMode =
        decisions.firstOrNull { it.path == path }?.readMode ?: WorkflowFormFieldAccessMode.DENY

    fun mayWrite(path: WorkflowFormFieldPath): Boolean =
        decisions.firstOrNull { it.path == path }?.writeMode == WorkflowFormFieldAccessMode.ALLOW

    override fun toString(): String = "WorkflowFormFieldAccessReport(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            decisions: Collection<WorkflowFormFieldAccessDecision>,
            authorityReceiptDigest: String,
        ): WorkflowFormFieldAccessReport = WorkflowFormFieldAccessReport(decisions, authorityReceiptDigest)
    }
}

/** Immutable submission metadata; canonical values stay behind a persistence boundary. */
class WorkflowFormSubmissionRef private constructor(
    submissionId: String,
    val version: Long,
    val form: WorkflowFormVersionRef,
    val submittedBy: WorkflowPrincipalRef,
    canonicalPayloadDigest: String,
    val payloadSizeBytes: Int,
    validationReceiptDigest: String,
    fieldAccessReceiptDigest: String,
    val submittedAtEpochMilli: Long,
) {
    val submissionId: String = WorkflowContractSupport.requireText(
        submissionId,
        WorkflowContractSupport.MAX_REFERENCE_ID_UTF8_BYTES,
        "Workflow form submission identifier is invalid.",
    )
    val canonicalPayloadDigest: String = WorkflowContractSupport.requireCanonicalSha256(
        canonicalPayloadDigest,
        "Workflow form submission payload digest is invalid.",
    )
    val validationReceiptDigest: String = WorkflowContractSupport.requireCanonicalSha256(
        validationReceiptDigest,
        "Workflow form validation receipt digest is invalid.",
    )
    val fieldAccessReceiptDigest: String = WorkflowContractSupport.requireCanonicalSha256(
        fieldAccessReceiptDigest,
        "Workflow form field-access receipt digest is invalid.",
    )
    val submissionDigest: String

    init {
        require(version >= 0L) { "Workflow form submission version is invalid." }
        require(payloadSizeBytes in 1..4 * 1024 * 1024) { "Workflow form submission size is invalid." }
        require(submittedAtEpochMilli >= 0L) { "Workflow form submission time is invalid." }
        submissionDigest = WorkflowContractSupport.digest("flowweft-workflow-form-submission-ref-v1")
            .text(this.submissionId)
            .longValue(version)
            .text(form.bindingDigest)
            .text(submittedBy.type)
            .text(submittedBy.id)
            .text(this.canonicalPayloadDigest)
            .integer(payloadSizeBytes)
            .text(this.validationReceiptDigest)
            .text(this.fieldAccessReceiptDigest)
            .longValue(submittedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "WorkflowFormSubmissionRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            submissionId: String,
            version: Long,
            form: WorkflowFormVersionRef,
            submittedBy: WorkflowPrincipalRef,
            canonicalPayloadDigest: String,
            payloadSizeBytes: Int,
            validationReceiptDigest: String,
            fieldAccessReceiptDigest: String,
            submittedAtEpochMilli: Long,
        ): WorkflowFormSubmissionRef = WorkflowFormSubmissionRef(
            submissionId,
            version,
            form,
            submittedBy,
            canonicalPayloadDigest,
            payloadSizeBytes,
            validationReceiptDigest,
            fieldAccessReceiptDigest,
            submittedAtEpochMilli,
        )
    }
}
