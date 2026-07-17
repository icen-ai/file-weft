package ai.icen.fw.workflow.spi

import ai.icen.fw.workflow.api.WorkflowDefinition
import java.util.concurrent.CompletionStage

class WorkflowDefinitionMediaType private constructor(value: String) {
    val value: String = WorkflowSpiContractSupport.requireText(
        value, WorkflowSpiContractSupport.MAX_CODE_UTF8_BYTES, "Workflow definition media type is invalid.",
    ).also {
        require(it.all { character ->
            character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
                character == '/' || character == '.' || character == '+' || character == '-'
        }) { "Workflow definition media type is invalid." }
    }

    val baselineKnown: Boolean
        get() = this == FLOWWEFT_JSON || this == BPMN_XML

    override fun equals(other: Any?): Boolean = this === other || other is WorkflowDefinitionMediaType && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "WorkflowDefinitionMediaType(<redacted>)"

    companion object {
        @JvmField val FLOWWEFT_JSON = WorkflowDefinitionMediaType("application/vnd.flowweft.workflow+json")
        @JvmField val BPMN_XML = WorkflowDefinitionMediaType("application/vnd.omg.bpmn20+xml")

        @JvmStatic fun of(value: String): WorkflowDefinitionMediaType = when (value) {
            FLOWWEFT_JSON.value -> FLOWWEFT_JSON
            BPMN_XML.value -> BPMN_XML
            else -> WorkflowDefinitionMediaType(value)
        }
    }
}

class WorkflowDefinitionFormatRef private constructor(
    standardId: String,
    standardVersion: String,
    val mediaType: WorkflowDefinitionMediaType,
    schemaDigest: String,
) {
    val standardId: String = WorkflowSpiContractSupport.requireMachineCode(
        standardId, "Workflow definition standard identifier is invalid.",
    )
    val standardVersion: String = WorkflowSpiContractSupport.requireText(
        standardVersion,
        WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES,
        "Workflow definition standard version is invalid.",
    )
    val schemaDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        schemaDigest, "Workflow definition standard schema digest is invalid.",
    )
    val formatDigest: String = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-definition-format-v1")
        .text(this.standardId)
        .text(this.standardVersion)
        .text(mediaType.value)
        .text(this.schemaDigest)
        .finish()

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowDefinitionFormatRef && standardId == other.standardId &&
        standardVersion == other.standardVersion && mediaType == other.mediaType && schemaDigest == other.schemaDigest

    override fun hashCode(): Int {
        var result = standardId.hashCode()
        result = 31 * result + standardVersion.hashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + schemaDigest.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowDefinitionFormatRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            standardId: String,
            standardVersion: String,
            mediaType: WorkflowDefinitionMediaType,
            schemaDigest: String,
        ): WorkflowDefinitionFormatRef = WorkflowDefinitionFormatRef(
            standardId, standardVersion, mediaType, schemaDigest,
        )
    }
}

class WorkflowDefinitionCodecDescriptor private constructor(
    providerId: String,
    providerRevision: String,
    codecId: String,
    codecVersion: String,
    formats: Collection<WorkflowDefinitionFormatRef>,
    val maximumDocumentBytes: Int,
    val maximumConformanceEntries: Int,
) {
    val providerId: String = WorkflowSpiContractSupport.requireMachineCode(providerId, "Workflow codec provider is invalid.")
    val providerRevision: String = WorkflowSpiContractSupport.requireText(
        providerRevision, WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES, "Workflow codec provider revision is invalid.",
    )
    val codecId: String = WorkflowSpiContractSupport.requireMachineCode(codecId, "Workflow codec identifier is invalid.")
    val codecVersion: String = WorkflowSpiContractSupport.requireText(
        codecVersion, WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES, "Workflow codec version is invalid.",
    )
    val formats: List<WorkflowDefinitionFormatRef> = WorkflowSpiContractSupport.immutableList(
        formats, WorkflowSpiContractSupport.MAX_ITEMS, "Workflow codec formats exceed the limit.",
    )
    val descriptorDigest: String

    init {
        require(this.formats.isNotEmpty() && this.formats.toSet().size == this.formats.size) {
            "Workflow codec formats must be non-empty and unique."
        }
        require(this.formats.all { it.mediaType.baselineKnown }) {
            "Unknown workflow definition media types require future typed support."
        }
        require(maximumDocumentBytes in 1..WorkflowSpiContractSupport.MAX_PAYLOAD_BYTES) {
            "Workflow codec document limit is invalid."
        }
        require(maximumConformanceEntries in 1..WorkflowSpiContractSupport.MAX_ITEMS) {
            "Workflow codec conformance-entry limit is invalid."
        }
        descriptorDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-definition-codec-descriptor-v1")
            .text(this.providerId).text(this.providerRevision).text(this.codecId).text(this.codecVersion)
            .integer(this.formats.size)
            .also { writer -> this.formats.forEach { writer.text(it.formatDigest) } }
            .integer(maximumDocumentBytes)
            .integer(maximumConformanceEntries)
            .finish()
    }

    override fun toString(): String = "WorkflowDefinitionCodecDescriptor(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            providerId: String,
            providerRevision: String,
            codecId: String,
            codecVersion: String,
            formats: Collection<WorkflowDefinitionFormatRef>,
            maximumDocumentBytes: Int,
            maximumConformanceEntries: Int,
        ): WorkflowDefinitionCodecDescriptor = WorkflowDefinitionCodecDescriptor(
            providerId, providerRevision, codecId, codecVersion, formats,
            maximumDocumentBytes, maximumConformanceEntries,
        )
    }
}

class WorkflowDefinitionDocument private constructor(
    val format: WorkflowDefinitionFormatRef,
    content: ByteArray,
) {
    private val content: ByteArray = WorkflowSpiContractSupport.immutableBytes(
        content, WorkflowSpiContractSupport.MAX_PAYLOAD_BYTES, "Workflow definition document is empty or exceeds the limit.",
    )
    val size: Int = this.content.size
    val contentDigest: String = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-definition-document-v1")
        .text(format.formatDigest)
        .bytes(this.content)
        .finish()

    fun bytes(): ByteArray = content.copyOf()

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowDefinitionDocument && format == other.format && content.contentEquals(other.content)
    override fun hashCode(): Int = 31 * format.hashCode() + content.contentHashCode()
    override fun toString(): String = "WorkflowDefinitionDocument(<redacted>)"

    companion object {
        @JvmStatic fun of(format: WorkflowDefinitionFormatRef, content: ByteArray): WorkflowDefinitionDocument =
            WorkflowDefinitionDocument(format, content)
    }
}

class WorkflowConformanceStatus private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(code, "Workflow conformance status is invalid.")
    val executable: Boolean
        get() = this == SUPPORTED
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowConformanceStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowConformanceStatus(<redacted>)"

    companion object {
        @JvmField val SUPPORTED = WorkflowConformanceStatus("supported")
        @JvmField val LOSSY = WorkflowConformanceStatus("lossy")
        @JvmField val UNSUPPORTED = WorkflowConformanceStatus("unsupported")
        @JvmStatic fun of(code: String): WorkflowConformanceStatus = when (code) {
            SUPPORTED.code -> SUPPORTED
            LOSSY.code -> LOSSY
            UNSUPPORTED.code -> UNSUPPORTED
            else -> WorkflowConformanceStatus(code)
        }
    }
}

/** One declared semantic feature in the complete source manifest. */
class WorkflowConformanceFeatureRef private constructor(
    elementRef: String,
    featureCode: String,
) {
    val elementRef: String = WorkflowSpiContractSupport.requireOpaqueReference(
        elementRef, "Workflow conformance element reference is invalid.",
    )
    val featureCode: String = WorkflowSpiContractSupport.requireMachineCode(
        featureCode, "Workflow conformance feature code is invalid.",
    )
    val featureDigest: String = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-conformance-feature-v1")
        .text(this.elementRef)
        .text(this.featureCode)
        .finish()

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowConformanceFeatureRef && elementRef == other.elementRef && featureCode == other.featureCode
    override fun hashCode(): Int = 31 * elementRef.hashCode() + featureCode.hashCode()
    override fun toString(): String = "WorkflowConformanceFeatureRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(elementRef: String, featureCode: String): WorkflowConformanceFeatureRef =
            WorkflowConformanceFeatureRef(elementRef, featureCode)
    }
}

/**
 * Complete source coverage assertion. The manifest must name every parsed element and
 * semantic feature; a conformance report is executable only when it covers this exact set.
 */
class WorkflowConformanceCoverage private constructor(
    val elementCount: Int,
    manifest: Collection<WorkflowConformanceFeatureRef>,
) {
    val manifest: List<WorkflowConformanceFeatureRef> = WorkflowSpiContractSupport.immutableList(
        manifest,
        WorkflowSpiContractSupport.MAX_ITEMS,
        "Workflow conformance manifest exceeds the limit.",
    )
    val featureCount: Int = this.manifest.size
    val manifestDigest: String
    val coverageDigest: String

    init {
        require(elementCount in 1..WorkflowSpiContractSupport.MAX_ITEMS) {
            "Workflow conformance element count is invalid."
        }
        require(this.manifest.isNotEmpty() && this.manifest.toSet().size == this.manifest.size) {
            "Workflow conformance manifest must be non-empty and unique."
        }
        require(this.manifest.map { it.elementRef }.toSet().size == elementCount) {
            "Workflow conformance manifest must cover every declared source element."
        }
        manifestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-conformance-manifest-v1")
            .integer(elementCount)
            .integer(featureCount)
            .also { writer -> this.manifest.forEach { writer.text(it.featureDigest) } }
            .finish()
        coverageDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-conformance-coverage-v1")
            .integer(elementCount)
            .integer(featureCount)
            .text(manifestDigest)
            .finish()
    }

    internal fun covers(entries: Collection<WorkflowConformanceEntry>): Boolean =
        entries.size == featureCount && entries.map { it.featureRef }.toSet() == manifest.toSet()

    override fun toString(): String = "WorkflowConformanceCoverage(<redacted>)"

    companion object {
        @JvmStatic
        fun complete(
            elementCount: Int,
            manifest: Collection<WorkflowConformanceFeatureRef>,
        ): WorkflowConformanceCoverage = WorkflowConformanceCoverage(elementCount, manifest)
    }
}

/** Value-free, element-level codec evidence suitable for durable storage. */
class WorkflowConformanceEntry private constructor(
    elementRef: String,
    featureCode: String,
    val status: WorkflowConformanceStatus,
    reasonCode: String,
) {
    val elementRef: String = WorkflowSpiContractSupport.requireOpaqueReference(
        elementRef, "Workflow conformance element reference is invalid.",
    )
    val featureCode: String = WorkflowSpiContractSupport.requireMachineCode(
        featureCode, "Workflow conformance feature code is invalid.",
    )
    val reasonCode: String = WorkflowSpiContractSupport.requireMachineCode(
        reasonCode, "Workflow conformance reason code is invalid.",
    )
    val featureRef: WorkflowConformanceFeatureRef = WorkflowConformanceFeatureRef.of(this.elementRef, this.featureCode)
    val entryDigest: String = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-conformance-entry-v1")
        .text(this.elementRef).text(this.featureCode).text(status.code).text(this.reasonCode).finish()

    override fun toString(): String = "WorkflowConformanceEntry(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            elementRef: String,
            featureCode: String,
            status: WorkflowConformanceStatus,
            reasonCode: String,
        ): WorkflowConformanceEntry = WorkflowConformanceEntry(elementRef, featureCode, status, reasonCode)
    }
}

class WorkflowDefinitionConformanceReport private constructor(
    val format: WorkflowDefinitionFormatRef,
    sourceDigest: String,
    codecDescriptorDigest: String,
    entries: Collection<WorkflowConformanceEntry>,
    val coverage: WorkflowConformanceCoverage?,
) {
    val sourceDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        sourceDigest, "Workflow conformance source digest is invalid.",
    )
    val codecDescriptorDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        codecDescriptorDigest, "Workflow conformance codec descriptor digest is invalid.",
    )
    val entries: List<WorkflowConformanceEntry> = WorkflowSpiContractSupport.immutableList(
        entries, WorkflowSpiContractSupport.MAX_ITEMS, "Workflow conformance entries exceed the limit.",
    )
    init {
        require(this.entries.map { it.elementRef + '\u0000' + it.featureCode }.toSet().size == this.entries.size) {
            "Workflow conformance entries must identify unique element features."
        }
        require(coverage == null || coverage.covers(this.entries)) {
            "Workflow conformance entries do not cover the complete source manifest."
        }
    }
    val complete: Boolean = coverage != null
    val executable: Boolean = complete && this.entries.isNotEmpty() && this.entries.all { it.status.executable }
    val reportDigest: String = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-conformance-report-v2")
        .text(format.formatDigest).text(this.sourceDigest).text(this.codecDescriptorDigest)
        .integer(this.entries.size)
        .also { writer -> this.entries.forEach { writer.text(it.entryDigest) } }
        .optionalText(coverage?.coverageDigest)
        .booleanValue(executable)
        .finish()

    override fun toString(): String = "WorkflowDefinitionConformanceReport(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            format: WorkflowDefinitionFormatRef,
            sourceDigest: String,
            codecDescriptorDigest: String,
            entries: Collection<WorkflowConformanceEntry>,
        ): WorkflowDefinitionConformanceReport = WorkflowDefinitionConformanceReport(
            format, sourceDigest, codecDescriptorDigest, entries, null,
        )

        @JvmStatic
        fun complete(
            format: WorkflowDefinitionFormatRef,
            sourceDigest: String,
            codecDescriptorDigest: String,
            entries: Collection<WorkflowConformanceEntry>,
            coverage: WorkflowConformanceCoverage,
        ): WorkflowDefinitionConformanceReport = WorkflowDefinitionConformanceReport(
            format, sourceDigest, codecDescriptorDigest, entries, coverage,
        )
    }
}

class WorkflowDefinitionDecodeRequest private constructor(
    val context: WorkflowProviderCallContext,
    val descriptor: WorkflowDefinitionCodecDescriptor,
    val document: WorkflowDefinitionDocument,
) {
    val requestDigest: String

    init {
        require(context.providerId == descriptor.providerId && context.providerRevision == descriptor.providerRevision) {
            "Workflow codec descriptor does not match the provider context."
        }
        require(descriptor.formats.contains(document.format) && document.size <= descriptor.maximumDocumentBytes &&
            document.size <= context.maximumInputBytes
        ) { "Workflow definition document does not match the codec or call limits." }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-definition-decode-request-v1")
            .text(context.contextDigest).text(descriptor.descriptorDigest).text(document.contentDigest).finish()
    }

    override fun toString(): String = "WorkflowDefinitionDecodeRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowProviderCallContext,
            descriptor: WorkflowDefinitionCodecDescriptor,
            document: WorkflowDefinitionDocument,
        ): WorkflowDefinitionDecodeRequest = WorkflowDefinitionDecodeRequest(context, descriptor, document)
    }
}

class WorkflowDefinitionEncodeRequest private constructor(
    val context: WorkflowProviderCallContext,
    val descriptor: WorkflowDefinitionCodecDescriptor,
    val definition: WorkflowDefinition,
    val targetFormat: WorkflowDefinitionFormatRef,
) {
    val requestDigest: String

    init {
        require(context.providerId == descriptor.providerId && context.providerRevision == descriptor.providerRevision) {
            "Workflow codec descriptor does not match the provider context."
        }
        require(definition.tenantId == context.tenantId) { "Workflow definition tenant does not match the call context." }
        require(descriptor.formats.contains(targetFormat)) { "Workflow codec does not declare the target format." }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-definition-encode-request-v1")
            .text(context.contextDigest).text(descriptor.descriptorDigest)
            .text(definition.contentDigest).text(targetFormat.formatDigest).finish()
    }

    override fun toString(): String = "WorkflowDefinitionEncodeRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowProviderCallContext,
            descriptor: WorkflowDefinitionCodecDescriptor,
            definition: WorkflowDefinition,
            targetFormat: WorkflowDefinitionFormatRef,
        ): WorkflowDefinitionEncodeRequest = WorkflowDefinitionEncodeRequest(context, descriptor, definition, targetFormat)
    }
}

class WorkflowDefinitionDecodeResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val report: WorkflowDefinitionConformanceReport,
    val definition: WorkflowDefinition?,
) {
    init {
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (definition != null)) {
            "Workflow definition decode result does not match its outcome."
        }
        require(definition == null || report.executable) {
            "Non-conformant workflow definitions cannot be returned as successful decode results."
        }
    }

    override fun toString(): String = "WorkflowDefinitionDecodeResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowDefinitionDecodeRequest,
            report: WorkflowDefinitionConformanceReport,
            definition: WorkflowDefinition,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowDefinitionDecodeResult {
            require(report.executable && report.format == request.document.format &&
                report.sourceDigest == request.document.contentDigest &&
                report.codecDescriptorDigest == request.descriptor.descriptorDigest
            ) { "Workflow decode conformance report is not bound to the request or is not executable." }
            require(report.entries.size <= request.descriptor.maximumConformanceEntries &&
                report.entries.size <= request.context.maximumItems && definition.tenantId == request.context.tenantId
            ) { "Workflow decode result exceeds limits or has a different tenant." }
            val digest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-definition-decode-result-v1")
                .text(report.reportDigest).text(definition.contentDigest).finish()
            return WorkflowDefinitionDecodeResult(
                WorkflowProviderReceipt.success(
                    request.context, request.requestDigest, digest, completedAtEpochMilli, expiresAtEpochMilli,
                ),
                report,
                definition,
            )
        }

        @JvmStatic
        fun failure(
            request: WorkflowDefinitionDecodeRequest,
            report: WorkflowDefinitionConformanceReport,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowDefinitionDecodeResult {
            require(report.format == request.document.format && report.sourceDigest == request.document.contentDigest &&
                report.codecDescriptorDigest == request.descriptor.descriptorDigest
            ) { "Workflow decode conformance report is not bound to the request." }
            require(report.entries.size <= request.descriptor.maximumConformanceEntries &&
                report.entries.size <= request.context.maximumItems
            ) { "Workflow decode conformance report exceeds the call limits." }
            val digest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-definition-decode-result-v1")
                .text(report.reportDigest).text(failure.code).booleanValue(failure.retryable).finish()
            return WorkflowDefinitionDecodeResult(
                WorkflowProviderReceipt.failure(
                    request.context, request.requestDigest, outcome, digest, failure,
                    completedAtEpochMilli, expiresAtEpochMilli,
                ),
                report,
                null,
            )
        }
    }
}

class WorkflowDefinitionEncodeResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val report: WorkflowDefinitionConformanceReport,
    val document: WorkflowDefinitionDocument?,
) {
    init {
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (document != null)) {
            "Workflow definition encode result does not match its outcome."
        }
        require(document == null || report.executable) {
            "Lossy or unsupported workflow exports cannot be returned as successful results."
        }
    }

    override fun toString(): String = "WorkflowDefinitionEncodeResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowDefinitionEncodeRequest,
            report: WorkflowDefinitionConformanceReport,
            document: WorkflowDefinitionDocument,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowDefinitionEncodeResult {
            require(report.executable && report.format == request.targetFormat && document.format == request.targetFormat &&
                report.sourceDigest == request.definition.contentDigest &&
                report.codecDescriptorDigest == request.descriptor.descriptorDigest
            ) { "Workflow encode result is not bound to the request or is not executable." }
            require(report.entries.size <= request.descriptor.maximumConformanceEntries &&
                report.entries.size <= request.context.maximumItems &&
                document.size <= request.descriptor.maximumDocumentBytes && document.size <= request.context.maximumOutputBytes
            ) { "Workflow encode result exceeds the call limits." }
            val digest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-definition-encode-result-v1")
                .text(report.reportDigest).text(document.contentDigest).finish()
            return WorkflowDefinitionEncodeResult(
                WorkflowProviderReceipt.success(
                    request.context, request.requestDigest, digest, completedAtEpochMilli, expiresAtEpochMilli,
                ),
                report,
                document,
            )
        }

        @JvmStatic
        fun failure(
            request: WorkflowDefinitionEncodeRequest,
            report: WorkflowDefinitionConformanceReport,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowDefinitionEncodeResult {
            require(report.format == request.targetFormat && report.sourceDigest == request.definition.contentDigest &&
                report.codecDescriptorDigest == request.descriptor.descriptorDigest
            ) { "Workflow encode conformance report is not bound to the request." }
            require(report.entries.size <= request.descriptor.maximumConformanceEntries &&
                report.entries.size <= request.context.maximumItems
            ) { "Workflow encode conformance report exceeds the call limits." }
            val digest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-definition-encode-result-v1")
                .text(report.reportDigest).text(failure.code).booleanValue(failure.retryable).finish()
            return WorkflowDefinitionEncodeResult(
                WorkflowProviderReceipt.failure(
                    request.context, request.requestDigest, outcome, digest, failure,
                    completedAtEpochMilli, expiresAtEpochMilli,
                ),
                report,
                null,
            )
        }
    }
}

interface WorkflowDefinitionCodec {
    /** Pure metadata lookup; it must not read tenant state or external resources. */
    fun descriptor(): WorkflowDefinitionCodecDescriptor

    fun decode(request: WorkflowDefinitionDecodeRequest): CompletionStage<WorkflowDefinitionDecodeResult>

    fun encode(request: WorkflowDefinitionEncodeRequest): CompletionStage<WorkflowDefinitionEncodeResult>
}
