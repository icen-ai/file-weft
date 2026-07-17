package ai.icen.fw.retrieval.spi

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.ExecutableRetrievalRequest
import ai.icen.fw.retrieval.api.RetrievalAccessProfile
import ai.icen.fw.retrieval.api.RetrievalCall
import ai.icen.fw.retrieval.api.RetrievalEvidenceRef
import java.util.Collections
import java.util.LinkedHashSet

/**
 * Exact capability snapshot for the host's read-only filename projection.
 *
 * The safe built-in retriever accepts only a local projection with stable cursor pagination. A
 * host that must send document metadata off-host needs a separately reviewed retrieval provider;
 * it cannot opt into the built-in path by changing runtime configuration.
 */
class FilenameCatalogDescriptor private constructor(
    providerTypeId: String,
    providerInstanceId: String,
    configurationDigest: String,
    securityDomainDigest: String,
    capabilityRevision: String,
    val maximumPageSize: Int,
    val maximumAuthorizedDocumentIds: Int,
    val sendsMetadataOffHost: Boolean,
    val supportsCancellation: Boolean,
    val supportsStableCursorPagination: Boolean,
) {
    val providerTypeId: String = requireSpiText(
        providerTypeId,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Filename catalog provider type is invalid.",
    )
    val providerInstanceId: String = requireSpiText(
        providerInstanceId,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Filename catalog provider instance is invalid.",
    )
    val configurationDigest: String = requireSpiSha256(
        configurationDigest,
        "Filename catalog configuration digest is invalid.",
    )
    val securityDomainDigest: String = requireSpiSha256(
        securityDomainDigest,
        "Filename catalog security-domain digest is invalid.",
    )
    val capabilityRevision: String = requireSpiText(
        capabilityRevision,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Filename catalog capability revision is invalid.",
    )
    val digest: String

    init {
        require(maximumPageSize in 1..MAX_FILENAME_PAGE_SIZE) {
            "Filename catalog page limit is invalid."
        }
        require(maximumAuthorizedDocumentIds in 1..MAX_FILENAME_AUTHORIZED_DOCUMENTS) {
            "Filename catalog authorized-document limit is invalid."
        }
        digest = RetrievalSpiDigest("flowweft-filename-catalog-descriptor-v1")
            .text(this.providerTypeId)
            .text(this.providerInstanceId)
            .text(this.configurationDigest)
            .text(this.securityDomainDigest)
            .text(this.capabilityRevision)
            .integer(maximumPageSize)
            .integer(maximumAuthorizedDocumentIds)
            .boolean(sendsMetadataOffHost)
            .boolean(supportsCancellation)
            .boolean(supportsStableCursorPagination)
            .finish()
    }

    override fun toString(): String = "FilenameCatalogDescriptor(providerType=$providerTypeId)"

    companion object {
        const val MAX_FILENAME_PAGE_SIZE: Int = 1_000
        const val MAX_FILENAME_AUTHORIZED_DOCUMENTS: Int = 50_000

        @JvmStatic
        fun create(
            providerTypeId: String,
            providerInstanceId: String,
            configurationDigest: String,
            securityDomainDigest: String,
            capabilityRevision: String,
            maximumPageSize: Int,
            maximumAuthorizedDocumentIds: Int,
            sendsMetadataOffHost: Boolean,
            supportsCancellation: Boolean,
            supportsStableCursorPagination: Boolean,
        ): FilenameCatalogDescriptor = FilenameCatalogDescriptor(
            providerTypeId,
            providerInstanceId,
            configurationDigest,
            securityDomainDigest,
            capabilityRevision,
            maximumPageSize,
            maximumAuthorizedDocumentIds,
            sendsMetadataOffHost,
            supportsCancellation,
            supportsStableCursorPagination,
        )
    }
}

/**
 * Content-free scan request for one authorization-filtered catalog page.
 *
 * It deliberately has no query, filename, principal attributes, action or purpose. The built-in
 * matcher therefore keeps the user's query on-host while the catalog sees only trusted tenant and
 * exact authorized document identifiers already produced by the authorization planner.
 */
class FilenameCatalogScanRequest private constructor(
    val requestId: Identifier,
    val executableRequestDigest: String,
    val candidateProviderDescriptorDigest: String,
    val catalogDescriptorDigest: String,
    val tenantId: Identifier,
    authorizedDocumentIds: Collection<Identifier>,
    val maximumEntries: Int,
    val cursorToken: String?,
    val expectedSnapshotGeneration: String?,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
) {
    val authorizedDocumentIds: Set<Identifier> = immutableIdentifierSet(authorizedDocumentIds)
    val digest: String

    init {
        requireSpiIdentifier(requestId, "Filename catalog request id is invalid.")
        requireSpiSha256(executableRequestDigest, "Filename catalog executable digest is invalid.")
        requireSpiSha256(
            candidateProviderDescriptorDigest,
            "Filename catalog candidate-provider descriptor digest is invalid.",
        )
        requireSpiSha256(catalogDescriptorDigest, "Filename catalog descriptor digest is invalid.")
        requireSpiIdentifier(tenantId, "Filename catalog tenant id is invalid.")
        require(this.authorizedDocumentIds.isNotEmpty() &&
            this.authorizedDocumentIds.size <= FilenameCatalogDescriptor.MAX_FILENAME_AUTHORIZED_DOCUMENTS) {
            "Filename catalog authorized-document set is invalid."
        }
        require(maximumEntries in 1..FilenameCatalogDescriptor.MAX_FILENAME_PAGE_SIZE) {
            "Filename catalog requested page size is invalid."
        }
        cursorToken?.let(::requireFilenameCursorToken)
        expectedSnapshotGeneration?.let {
            requireSpiText(
                it,
                RetrievalSpiLimits.MAX_ID_CODE_POINTS,
                "Filename catalog expected snapshot generation is invalid.",
            )
        }
        requireSpiTime(requestedAtEpochMilli, "Filename catalog request time is invalid.")
        require(deadlineEpochMilli > requestedAtEpochMilli) { "Filename catalog deadline is invalid." }
        digest = RetrievalSpiDigest("flowweft-filename-catalog-scan-request-v1")
            .text(requestId.value)
            .text(executableRequestDigest)
            .text(candidateProviderDescriptorDigest)
            .text(catalogDescriptorDigest)
            .text(tenantId.value)
            .integer(this.authorizedDocumentIds.size)
            .apply { this@FilenameCatalogScanRequest.authorizedDocumentIds.map { it.value }.sorted().forEach(::text) }
            .integer(maximumEntries)
            .optionalText(cursorToken)
            .optionalText(expectedSnapshotGeneration)
            .long(requestedAtEpochMilli)
            .long(deadlineEpochMilli)
            .finish()
    }

    override fun toString(): String = "FilenameCatalogScanRequest(documents=${authorizedDocumentIds.size})"

    companion object {
        /** Creates the only scan shape accepted by the built-in filename retriever. */
        @JvmStatic
        fun create(
            request: ExecutableRetrievalRequest,
            descriptor: FilenameCatalogDescriptor,
        ): FilenameCatalogScanRequest {
            require(request.accessProfile == RetrievalAccessProfile.AUTHORIZED_ID_SET) {
                "Built-in filename search requires an exact authorized-document set."
            }
            require(request.authorizedDocumentIds.size <= descriptor.maximumAuthorizedDocumentIds) {
                "Authorized-document set exceeds filename catalog capability."
            }
            require(request.candidateLimit <= descriptor.maximumPageSize) {
                "Retrieval limit exceeds filename catalog page capability."
            }
            val cursor = request.pageCursor
            return FilenameCatalogScanRequest(
                request.attemptId,
                request.digest,
                request.providerDescriptorDigest,
                descriptor.digest,
                request.tenantId,
                request.authorizedDocumentIds,
                request.candidateLimit,
                cursor?.opaqueToken,
                cursor?.indexGeneration,
                request.preparedAtEpochMilli,
                minOf(request.deadlineEpochMilli, request.accessPlanExpiresAtEpochMilli),
            )
        }
    }
}

/** One document-level filename projection row. No content, title, highlight or score is allowed. */
class FilenameCatalogEntry private constructor(
    fileName: String,
    sortKey: String,
    val evidence: RetrievalEvidenceRef,
) {
    val fileName: String = requireSpiText(fileName, MAX_FILENAME_CODE_POINTS, "Catalog filename is invalid.")
    val sortKey: String = requireFilenameSortKey(sortKey)
    val digest: String

    init {
        require(evidence.chunkId == null) { "Filename catalog rows require document-level evidence." }
        digest = RetrievalSpiDigest("flowweft-filename-catalog-entry-v1")
            .text(this.fileName)
            .text(this.sortKey)
            .text(evidence.digest)
            .finish()
    }

    override fun toString(): String = "FilenameCatalogEntry(<redacted>)"

    companion object {
        const val MAX_FILENAME_CODE_POINTS: Int = 1_024

        @JvmStatic
        fun create(fileName: String, sortKey: String, evidence: RetrievalEvidenceRef): FilenameCatalogEntry =
            FilenameCatalogEntry(fileName, sortKey, evidence)
    }
}

/** Exact response page bound to one scan request and one catalog capability snapshot. */
class FilenameCatalogPage private constructor(
    val requestId: Identifier,
    requestDigest: String,
    catalogDescriptorDigest: String,
    snapshotGeneration: String,
    entries: Collection<FilenameCatalogEntry>,
    nextCursorToken: String?,
    val completedAtEpochMilli: Long,
) {
    val requestDigest: String = requireSpiSha256(requestDigest, "Filename catalog page request digest is invalid.")
    val catalogDescriptorDigest: String = requireSpiSha256(
        catalogDescriptorDigest,
        "Filename catalog page descriptor digest is invalid.",
    )
    val snapshotGeneration: String = requireSpiText(
        snapshotGeneration,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Filename catalog snapshot generation is invalid.",
    )
    val entries: List<FilenameCatalogEntry> = immutableSpiList(
        entries,
        FilenameCatalogDescriptor.MAX_FILENAME_PAGE_SIZE,
        "Filename catalog page contains too many rows.",
    )
    val nextCursorToken: String? = nextCursorToken?.also(::requireFilenameCursorToken)
    val digest: String

    init {
        requireSpiIdentifier(requestId, "Filename catalog page request id is invalid.")
        requireSpiTime(completedAtEpochMilli, "Filename catalog completion time is invalid.")
        require(this.entries.map { it.sortKey }.zipWithNext().all { (left, right) -> left < right }) {
            "Filename catalog rows must use unique, strictly increasing stable sort keys."
        }
        require(this.entries.map { it.evidence.digest }.toSet().size == this.entries.size) {
            "Filename catalog page contains duplicate evidence."
        }
        require(this.entries.all { it.evidence.indexGeneration == this.snapshotGeneration }) {
            "Filename catalog row generation does not match the page snapshot."
        }
        val writer = RetrievalSpiDigest("flowweft-filename-catalog-page-v1")
            .text(requestId.value)
            .text(this.requestDigest)
            .text(this.catalogDescriptorDigest)
            .text(this.snapshotGeneration)
            .integer(this.entries.size)
        this.entries.forEach { writer.text(it.digest) }
        digest = writer.optionalText(this.nextCursorToken).long(completedAtEpochMilli).finish()
    }

    fun requireValidFor(request: FilenameCatalogScanRequest, descriptor: FilenameCatalogDescriptor) {
        require(requestId == request.requestId && requestDigest == request.digest) {
            "Filename catalog page belongs to another scan request."
        }
        require(catalogDescriptorDigest == descriptor.digest &&
            request.catalogDescriptorDigest == descriptor.digest) {
            "Filename catalog page belongs to another descriptor."
        }
        require(completedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli) {
            "Filename catalog page completed outside its request window."
        }
        require(entries.size <= request.maximumEntries) { "Filename catalog page exceeds its requested limit." }
        require(entries.all { entry ->
            entry.evidence.tenantId == request.tenantId &&
                entry.evidence.documentId in request.authorizedDocumentIds
        }) { "Filename catalog page contains a tenant or document outside the authorized set." }
        require(request.expectedSnapshotGeneration == null ||
            request.expectedSnapshotGeneration == snapshotGeneration) {
            "Filename catalog cursor cannot cross a snapshot-generation switch."
        }
        require(nextCursorToken == null || descriptor.supportsStableCursorPagination) {
            "Filename catalog returned a cursor without declaring stable pagination."
        }
        require(nextCursorToken == null || nextCursorToken != request.cursorToken) {
            "Filename catalog cursor did not make progress."
        }
    }

    override fun toString(): String = "FilenameCatalogPage(rows=${entries.size})"

    companion object {
        @JvmStatic
        fun success(
            request: FilenameCatalogScanRequest,
            descriptor: FilenameCatalogDescriptor,
            snapshotGeneration: String,
            entries: Collection<FilenameCatalogEntry>,
            nextCursorToken: String?,
            completedAtEpochMilli: Long,
        ): FilenameCatalogPage = FilenameCatalogPage(
            request.requestId,
            request.digest,
            descriptor.digest,
            snapshotGeneration,
            entries,
            nextCursorToken,
            completedAtEpochMilli,
        ).also { page -> page.requireValidFor(request, descriptor) }
    }
}

/** Host-provided, read-only document filename projection. */
interface FilenameCatalog {
    fun descriptor(): FilenameCatalogDescriptor
    fun scan(request: FilenameCatalogScanRequest): RetrievalCall<FilenameCatalogPage>
}

private fun immutableIdentifierSet(values: Collection<Identifier>): Set<Identifier> {
    val copy = LinkedHashSet(values)
    require(copy.size == values.size && copy.none { value -> value == null }) {
        "Filename catalog authorized-document set contains duplicates or null values."
    }
    copy.forEach { requireSpiIdentifier(it, "Filename catalog authorized document id is invalid.") }
    return Collections.unmodifiableSet(copy)
}

private fun requireFilenameCursorToken(value: String) {
    require(value.length in 1..MAX_FILENAME_CURSOR_LENGTH && value.all(::isFilenameOpaqueCharacter)) {
        "Filename catalog cursor must be a bounded URL-safe opaque value."
    }
}

private fun requireFilenameSortKey(value: String): String {
    require(value.length in 1..MAX_FILENAME_SORT_KEY_LENGTH && value.all(::isFilenameOpaqueCharacter)) {
        "Filename catalog sort key must be a bounded URL-safe stable value."
    }
    return value
}

private fun isFilenameOpaqueCharacter(character: Char): Boolean =
    character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
        character == '.' || character == '_' || character == '~' || character == '-'

private const val MAX_FILENAME_CURSOR_LENGTH: Int = 2_048
private const val MAX_FILENAME_SORT_KEY_LENGTH: Int = 512
