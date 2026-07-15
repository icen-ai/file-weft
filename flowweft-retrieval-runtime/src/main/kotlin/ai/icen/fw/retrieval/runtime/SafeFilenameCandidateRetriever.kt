package ai.icen.fw.retrieval.runtime

import ai.icen.fw.retrieval.api.CandidateRetriever
import ai.icen.fw.retrieval.api.CandidateRetrieverDescriptor
import ai.icen.fw.retrieval.api.ExecutableRetrievalRequest
import ai.icen.fw.retrieval.api.RetrievalAccessProfile
import ai.icen.fw.retrieval.api.RetrievalCall
import ai.icen.fw.retrieval.api.RetrievalCancellationOutcome
import ai.icen.fw.retrieval.api.RetrievalCancellationReason
import ai.icen.fw.retrieval.api.RetrievalCandidate
import ai.icen.fw.retrieval.api.RetrievalMode
import ai.icen.fw.retrieval.api.RetrievalResultEnvelope
import ai.icen.fw.retrieval.spi.FilenameCatalog
import ai.icen.fw.retrieval.spi.FilenameCatalogDescriptor
import ai.icen.fw.retrieval.spi.FilenameCatalogEntry
import ai.icen.fw.retrieval.spi.FilenameCatalogPage
import ai.icen.fw.retrieval.spi.FilenameCatalogScanRequest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CompletionStage

/**
 * Built-in, on-host filename matcher.
 *
 * The catalog receives no query and may return only rows in the exact authorized-document set.
 * Filenames are matched locally and never appear in the candidate envelope. The surrounding secure
 * runtime still performs authoritative lineage resolution and fresh per-candidate authorization
 * before hydration or result exposure.
 */
class SafeFilenameCandidateRetriever private constructor(
    private val catalog: FilenameCatalog,
    private val catalogSnapshot: FilenameCatalogDescriptor,
    private val fullTextFallback: Boolean,
) : CandidateRetriever {
    private val value: CandidateRetrieverDescriptor = candidateDescriptor(catalogSnapshot, fullTextFallback)

    override fun descriptor(): CandidateRetrieverDescriptor = value

    override fun start(request: ExecutableRetrievalRequest): RetrievalCall<RetrievalResultEnvelope> {
        require(request.providerDescriptorDigest == value.digest &&
            request.providerTypeId == value.providerTypeId &&
            request.providerInstanceId == value.providerInstanceId &&
            request.providerConfigurationDigest == value.configurationDigest) {
            "Filename retrieval request belongs to another provider snapshot."
        }
        require(request.accessProfile == RetrievalAccessProfile.AUTHORIZED_ID_SET) {
            "Built-in filename search requires an exact authorized-document set."
        }
        require(request.mode in value.supportedModes) { "Filename retriever does not support the requested mode." }
        val currentCatalog = requireNotNull(catalog.descriptor()) { "Filename catalog returned no descriptor." }
        requireSameCatalog(currentCatalog)
        val scanRequest = FilenameCatalogScanRequest.create(request, currentCatalog)
        val source = requireNotNull(catalog.scan(scanRequest)) { "Filename catalog returned no call handle." }
        val sourceCompletion = requireNotNull(source.completion()) {
            "Filename catalog returned no completion stage."
        }
        val mapped = sourceCompletion.thenApply { response ->
            val page = requireNotNull(response) { "Filename catalog completed without a page." }
            requireSameCatalog(requireNotNull(catalog.descriptor()) { "Filename catalog returned no descriptor." })
            page.requireValidFor(scanRequest, currentCatalog)
            toEnvelope(request, page)
        }
        return object : RetrievalCall<RetrievalResultEnvelope> {
            override fun completion(): CompletionStage<RetrievalResultEnvelope> = mapped

            override fun cancel(reason: RetrievalCancellationReason): CompletionStage<RetrievalCancellationOutcome> =
                requireNotNull(source.cancel(reason)) { "Filename catalog cancellation returned no completion stage." }
        }
    }

    private fun requireSameCatalog(actual: FilenameCatalogDescriptor) {
        require(actual.digest == catalogSnapshot.digest &&
            actual.providerTypeId == catalogSnapshot.providerTypeId &&
            actual.providerInstanceId == catalogSnapshot.providerInstanceId &&
            actual.configurationDigest == catalogSnapshot.configurationDigest &&
            actual.securityDomainDigest == catalogSnapshot.securityDomainDigest &&
            actual.capabilityRevision == catalogSnapshot.capabilityRevision) {
            "Filename catalog descriptor changed during retrieval."
        }
    }

    private fun toEnvelope(
        request: ExecutableRetrievalRequest,
        page: FilenameCatalogPage,
    ): RetrievalResultEnvelope {
        val query = normalizeFilename(request.query)
        val matches = page.entries.mapNotNull { entry -> match(request.mode, query, entry) }
            .sortedWith(
                compareByDescending<FilenameMatch> { it.score }
                    .thenBy { it.normalizedFilename }
                    .thenBy { it.entry.sortKey },
            )
        val candidates = matches.mapIndexed { index, matched ->
            RetrievalCandidate.create(matched.entry.evidence, request.mode, matched.score, index + 1)
        }
        return RetrievalResultEnvelope.create(
            request,
            value,
            page.snapshotGeneration,
            page.completedAtEpochMilli,
            candidates,
            page.nextCursorToken,
            page.nextCursorToken != null,
            false,
        )
    }

    private fun match(mode: RetrievalMode, query: String, entry: FilenameCatalogEntry): FilenameMatch? {
        val filename = normalizeFilename(entry.fileName)
        val score = when (mode) {
            RetrievalMode.EXACT_FILENAME -> if (filename == query) EXACT_SCORE else null
            RetrievalMode.PREFIX_FILENAME -> when {
                filename == query -> EXACT_SCORE
                filename.startsWith(query) -> PREFIX_SCORE
                else -> null
            }
            RetrievalMode.CONTAINS_FILENAME, RetrievalMode.FULL_TEXT -> when {
                filename == query -> EXACT_SCORE
                filename.startsWith(query) -> PREFIX_SCORE
                filename.contains(query) -> CONTAINS_SCORE
                else -> null
            }
            else -> null
        } ?: return null
        return FilenameMatch(entry, filename, score)
    }

    companion object {
        const val PROVIDER_TYPE_ID: String = "flowweft.safe-filename"
        const val FALLBACK_PROVIDER_TYPE_ID: String = "flowweft.safe-filename-fallback"

        /** Creates the normal built-in provider for explicit exact, prefix and contains modes. */
        @JvmStatic
        fun create(catalog: FilenameCatalog): SafeFilenameCandidateRetriever = create(catalog, false)

        /**
         * Creates the explicit missing-full-text-provider fallback.
         *
         * A FULL_TEXT request is treated as normalized filename-contains matching. The security
         * receipt exposes [FALLBACK_PROVIDER_TYPE_ID], so callers can diagnose the semantic fallback.
         * Vector and hybrid requests remain unsupported instead of receiving misleading semantics.
         */
        @JvmStatic
        fun createFullTextFallback(catalog: FilenameCatalog): SafeFilenameCandidateRetriever = create(catalog, true)

        private fun create(catalog: FilenameCatalog, fullTextFallback: Boolean): SafeFilenameCandidateRetriever {
            val descriptor = requireNotNull(catalog.descriptor()) { "Filename catalog returned no descriptor." }
            require(!descriptor.sendsMetadataOffHost) {
                "Built-in filename search forbids off-host document metadata egress."
            }
            require(descriptor.supportsStableCursorPagination) {
                "Built-in filename search requires stable cursor pagination."
            }
            return SafeFilenameCandidateRetriever(catalog, descriptor, fullTextFallback)
        }

        private fun candidateDescriptor(
            catalog: FilenameCatalogDescriptor,
            fullTextFallback: Boolean,
        ): CandidateRetrieverDescriptor {
            val providerType = if (fullTextFallback) FALLBACK_PROVIDER_TYPE_ID else PROVIDER_TYPE_ID
            val configurationDigest = RetrievalRuntimeDigest("flowweft-safe-filename-configuration-v1")
                .text(catalog.digest)
                .bool(fullTextFallback)
                .finish()
            val builder = CandidateRetrieverDescriptor.builder(
                providerType,
                catalog.providerInstanceId,
                configurationDigest,
                catalog.securityDomainDigest,
                "safe-filename-v1",
            )
                .tenantConstraint("trusted-tenant-id", "safe-filename-tenant-v1")
                .supportMode(RetrievalMode.EXACT_FILENAME)
                .supportMode(RetrievalMode.PREFIX_FILENAME)
                .supportMode(RetrievalMode.CONTAINS_FILENAME)
                .supportAccessProfile(RetrievalAccessProfile.AUTHORIZED_ID_SET)
                .limits(
                    minOf(catalog.maximumPageSize, MAX_RUNTIME_CANDIDATES),
                    catalog.maximumAuthorizedDocumentIds,
                )
                .queryEgress(false)
                .cancellation(catalog.supportsCancellation)
                .cursorPagination(true)
                .tenantAndAccessPreselectionGuaranteed(true)
            if (fullTextFallback) builder.supportMode(RetrievalMode.FULL_TEXT)
            return builder.build()
        }
    }
}

private class FilenameMatch(
    val entry: FilenameCatalogEntry,
    val normalizedFilename: String,
    val score: Double,
)

private fun normalizeFilename(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

private const val EXACT_SCORE: Double = 3.0
private const val PREFIX_SCORE: Double = 2.0
private const val CONTAINS_SCORE: Double = 1.0
