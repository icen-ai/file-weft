package ai.icen.fw.retrieval.spi

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.CandidateRetrieverDescriptor
import ai.icen.fw.retrieval.api.ExecutableRetrievalRequest
import ai.icen.fw.retrieval.api.RetrievalAccessProfile
import ai.icen.fw.retrieval.api.RetrievalCall
import ai.icen.fw.retrieval.api.RetrievalFailureCode
import ai.icen.fw.retrieval.api.RetrievalMode
import ai.icen.fw.retrieval.api.RetrievalResultEnvelope
import ai.icen.fw.retrieval.api.RetrievalRetryability
import java.io.ByteArrayInputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RetrievalSpiContractsTest {
    @Test
    fun `text vector and hybrid provider roles fail closed on advertised modes`() {
        val allModes = candidateDescriptor(
            RetrievalMode.FULL_TEXT,
            RetrievalMode.VECTOR,
            RetrievalMode.HYBRID,
        )
        val provider = object : TextSearchProvider, VectorSearchProvider, HybridSearchProvider {
            override fun descriptor(): CandidateRetrieverDescriptor = allModes
            override fun start(request: ExecutableRetrievalRequest): RetrievalCall<RetrievalResultEnvelope> =
                throw UnsupportedOperationException("not invoked by descriptor contract")
        }
        RetrievalProviderContracts.requireText(provider)
        RetrievalProviderContracts.requireVector(provider)
        RetrievalProviderContracts.requireHybrid(provider)

        val wrongVector = object : VectorSearchProvider {
            override fun descriptor(): CandidateRetrieverDescriptor = candidateDescriptor(RetrievalMode.FULL_TEXT)
            override fun start(request: ExecutableRetrievalRequest): RetrievalCall<RetrievalResultEnvelope> =
                throw UnsupportedOperationException("not invoked by descriptor contract")
        }
        assertFailsWith<IllegalArgumentException> { RetrievalProviderContracts.requireVector(wrongVector) }
    }

    @Test
    fun `extraction input is one-use and exact source lineage is enforced`() {
        val fixture = extractionFixture()
        val handle = fixture.request.openSource().toCompletableFuture().join()

        assertEquals(fixture.bytes.size.toLong(), handle.contentLength)
        assertEquals(fixture.text, handle.stream().readBytes().toString(Charsets.UTF_8))
        assertFailsWith<IllegalStateException> { handle.stream() }
        handle.close()
        handle.close()

        val mismatchedInputClosed = AtomicBoolean(false)
        val wrongSource = ContentBinarySource { source, _ ->
            CompletableFuture.completedFuture(
                ContentInputHandle.of(
                    object : ByteArrayInputStream(fixture.bytes) {
                        override fun close() {
                            mismatchedInputClosed.set(true)
                            super.close()
                        }
                    },
                    source.digest,
                    DIGEST_B,
                    fixture.bytes.size.toLong(),
                ),
            )
        }
        val wrongRequest = ContentExtractionRequest.of(
            Identifier("extract-wrong"),
            fixture.source,
            fixture.descriptor,
            DIGEST_C,
            1_000,
            10L,
            100L,
            wrongSource,
        )
        val mismatch = assertFailsWith<CompletionException> {
            wrongRequest.openSource().toCompletableFuture().join()
        }
        assertTrue(mismatch.cause is IllegalArgumentException)
        assertTrue(mismatchedInputClosed.get())
    }

    @Test
    fun `extraction source offsets and pages must be monotonic when supplied`() {
        val fixture = extractionFixture(supportsPageNumbers = true)
        val overlapping = listOf(
            ExtractedContentSegment.of(0, "合同", "text/plain", 0L, 6L, 2),
            ExtractedContentSegment.of(1, "正文", "text/plain", 5L, 12L, 2),
        )
        assertFailsWith<IllegalArgumentException> {
            ContentExtractionResult.success(fixture.request, overlapping, "extract-overlap", 20L)
        }

        val pageRegression = listOf(
            ExtractedContentSegment.of(0, "合同", "text/plain", pageNumber = 2),
            ExtractedContentSegment.of(1, "正文", "text/plain", pageNumber = 1),
        )
        assertFailsWith<IllegalArgumentException> {
            ContentExtractionResult.success(fixture.request, pageRegression, "extract-page", 20L)
        }
    }

    @Test
    fun `chunk offsets must reproduce complete extraction without gaps or lineage drift`() {
        val fixture = extractionFixture()
        val extraction = extraction(fixture)
        val descriptor = chunkerDescriptor()
        val request = ContentChunkingRequest.of(
            Identifier("chunk-1"),
            extraction,
            descriptor,
            DIGEST_D,
            8,
            8,
            2,
            30L,
            100L,
        )
        assertFailsWith<IllegalArgumentException> {
            ContentChunkingRequest.of(
                Identifier("chunk-before-extraction"),
                extraction,
                descriptor,
                DIGEST_D,
                8,
                8,
                2,
                19L,
                100L,
            )
        }
        val first = ContentChunk.of(0, 0, 0, 4, codePointSlice(fixture.text, 0, 4), "text/plain", fixture.sha)
        val second = ContentChunk.of(
            1,
            0,
            3,
            fixture.text.codePointCount(0, fixture.text.length),
            codePointSlice(fixture.text, 3, fixture.text.codePointCount(0, fixture.text.length)),
            "text/plain",
            fixture.sha,
        )

        val result = ContentChunkingResult.success(request, listOf(first, second), 40L)
        assertEquals(2, result.chunks.size)
        assertEquals(result.chunks.map { it.digest }, result.chunks.map { it.digest }.toList())
        assertFailsWith<UnsupportedOperationException> {
            (result.chunks as MutableList<ContentChunk>).clear()
        }

        val gap = ContentChunk.of(
            1,
            0,
            5,
            fixture.text.codePointCount(0, fixture.text.length),
            codePointSlice(fixture.text, 5, fixture.text.codePointCount(0, fixture.text.length)),
            "text/plain",
            fixture.sha,
        )
        assertFailsWith<IllegalArgumentException> {
            ContentChunkingResult.success(request, listOf(first, gap), 40L)
        }
        val drift = ContentChunk.of(1, 0, 3, 5, "错误", "text/plain", fixture.sha)
        assertFailsWith<IllegalArgumentException> {
            ContentChunkingResult.success(request, listOf(first, drift), 40L)
        }

        val noProgress = ContentChunk.of(
            1,
            0,
            0,
            fixture.text.codePointCount(0, fixture.text.length),
            fixture.text,
            "text/plain",
            fixture.sha,
        )
        assertFailsWith<IllegalArgumentException> {
            ContentChunkingResult.success(request, listOf(first, noProgress), 40L)
        }
    }

    @Test
    fun `embedding result binds exact order dimensions and finite values`() {
        val chunks = chunkingFixture()
        val descriptor = embeddingDescriptor()
        val request = EmbeddingRequest.of(
            Identifier("embedding-1"),
            descriptor,
            chunks.chunks.map(EmbeddingInput::from),
            50L,
            100L,
        )
        val vectors = request.inputs.mapIndexed { index, input ->
            EmbeddingVector.of(input, listOf(index + 0.1, index + 0.2, index + 0.3))
        }
        val result = EmbeddingResult.success(request, vectors, "provider-request-1", 60L)

        assertEquals(request.digest, result.requestDigest)
        assertEquals(3, result.vectors.first().values.size)
        assertFailsWith<IllegalArgumentException> {
            EmbeddingResult.success(request, vectors.reversed(), "provider-request-2", 60L)
        }
        assertFailsWith<IllegalArgumentException> {
            EmbeddingVector.of(request.inputs.first(), listOf(Double.NaN, 0.0, 1.0))
        }
    }

    @Test
    fun `generation stays invisible until complete seal and compare-and-set activation`() {
        val fixture = extractionFixture()
        val extraction = extraction(fixture)
        val chunks = chunking(extraction, fixture.text, fixture.sha)
        val embeddingDescriptor = embeddingDescriptor()
        val embeddingRequest = EmbeddingRequest.of(
            Identifier("embedding-index"),
            embeddingDescriptor,
            chunks.chunks.map(EmbeddingInput::from),
            50L,
            100L,
        )
        val vectors = embeddingRequest.inputs.map { input -> EmbeddingVector.of(input, listOf(0.1, 0.2, 0.3)) }
        val embeddingResult = EmbeddingResult.success(embeddingRequest, vectors, "embed-provider", 60L)
        val indexDescriptor = indexDescriptor()
        val manifest = RetrievalIndexGenerationManifest.of(
            "generation-2",
            fixture.source,
            extraction,
            chunks,
            indexDescriptor,
            "policy-9",
            DIGEST_E,
            embeddingRequest,
            embeddingResult,
        )
        assertFailsWith<IllegalArgumentException> {
            RetrievalIndexGenerationManifest.of(
                "generation-missing-vectors",
                fixture.source,
                extraction,
                chunks,
                indexDescriptor,
                "policy-9",
                DIGEST_E,
            )
        }
        val vectorOnlyManifest = RetrievalIndexGenerationManifest.of(
            "generation-vector-only",
            fixture.source,
            extraction,
            chunks,
            indexDescriptor(supportsText = false, supportsVectors = true),
            "policy-9",
            DIGEST_E,
            embeddingRequest,
            embeddingResult,
        )
        assertTrue(vectorOnlyManifest.records.all { record ->
            record.text == null && record.vector != null && record.sourceSha256 == fixture.sha
        })
        val textOnlyManifest = RetrievalIndexGenerationManifest.of(
            "generation-text-only",
            fixture.source,
            extraction,
            chunks,
            indexDescriptor(supportsText = true, supportsVectors = false),
            "policy-9",
            DIGEST_E,
        )
        assertTrue(textOnlyManifest.records.all { record -> record.text != null && record.vector == null })
        assertFailsWith<IllegalArgumentException> {
            RetrievalIndexStageBatch.of(
                Identifier("stage-invalid"),
                manifest,
                0,
                -1,
                1,
                70L,
                100L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RetrievalIndexStageBatch.of(
                Identifier("stage-before-embedding"),
                manifest,
                0,
                0,
                manifest.records.size,
                59L,
                100L,
            )
        }
        val duplicateStageId = Identifier("stage-duplicate")
        val duplicateStageFirst = RetrievalIndexStageBatch.of(
            duplicateStageId,
            manifest,
            0,
            0,
            1,
            70L,
            100L,
        )
        val duplicateStageSecond = RetrievalIndexStageBatch.of(
            duplicateStageId,
            manifest,
            1,
            1,
            manifest.records.size,
            70L,
            100L,
        )
        assertFailsWith<IllegalArgumentException> {
            RetrievalIndexSealRequest.of(
                Identifier("seal-duplicate-stage-id"),
                manifest,
                listOf(
                    RetrievalIndexStageReceipt.staged(duplicateStageFirst, "stage-first", 72L),
                    RetrievalIndexStageReceipt.staged(duplicateStageSecond, "stage-second", 73L),
                ),
                75L,
                110L,
            )
        }
        val batch = RetrievalIndexStageBatch.of(
            Identifier("stage-1"),
            manifest,
            0,
            0,
            manifest.records.size,
            70L,
            100L,
        )
        val staged = RetrievalIndexStageReceipt.staged(batch, "index-stage-provider", 75L)
        assertFalse(staged.visibleToQueries)
        val sealRequest = RetrievalIndexSealRequest.of(
            Identifier("seal-1"),
            manifest,
            listOf(staged),
            80L,
            110L,
        )
        val sealed = RetrievalIndexSealReceipt.sealed(sealRequest, "index-seal-provider", 85L)
        assertFalse(sealed.visibleToQueries)
        val activation = RetrievalIndexActivationRequest.of(
            Identifier("activate-1"),
            sealed,
            7L,
            90L,
            120L,
            "generation-1",
        )
        val baselineStateRequest = RetrievalIndexStateRequest.of(
            Identifier("state-before-activation"),
            indexDescriptor,
            fixture.source,
            61L,
            89L,
        )
        val baselineState = RetrievalIndexState.observed(
            baselineStateRequest,
            7L,
            65L,
            "generation-1",
            "policy-8",
            DIGEST_E,
        )
        val stateAfterFailureRequest = RetrievalIndexStateRequest.of(
            Identifier("state-after-activation-failure"),
            indexDescriptor,
            fixture.source,
            96L,
            110L,
        )
        val stateAfterFailure = RetrievalIndexState.observed(
            stateAfterFailureRequest,
            7L,
            100L,
            "generation-1",
            "policy-8",
            DIGEST_E,
        )
        val failureEvidence = RetrievalIndexGenerationFailureEvidence.afterActivationFailure(
            activation,
            baselineState,
            stateAfterFailure,
            RetrievalFailureCode.TEMPORARY_UNAVAILABLE,
            RetrievalRetryability.RETRYABLE,
        )
        assertSame(RetrievalIndexGenerationOperation.ACTIVATE, failureEvidence.operation)
        assertSame(RetrievalFailureCode.TEMPORARY_UNAVAILABLE, failureEvidence.failureCode)
        assertSame(RetrievalRetryability.RETRYABLE, failureEvidence.retryability)
        assertTrue(failureEvidence.retryable)
        assertEquals("generation-1", failureEvidence.observedState.activeGenerationId)
        val changedState = RetrievalIndexState.observed(
            stateAfterFailureRequest,
            8L,
            101L,
            "generation-1",
            "policy-8",
            DIGEST_E,
        )
        assertFailsWith<IllegalArgumentException> {
            RetrievalIndexGenerationFailureEvidence.afterActivationFailure(
                activation,
                baselineState,
                changedState,
                RetrievalFailureCode.INDEX_PROJECTION_CONFLICT,
                RetrievalRetryability.NOT_RETRYABLE,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            RetrievalIndexActivationReceipt.activated(
                activation,
                "another-generation",
                7L,
                "index-activate-provider",
                95L,
            )
        }
        val activated = RetrievalIndexActivationReceipt.activated(
            activation,
            "generation-1",
            7L,
            "index-activate-provider",
            95L,
        )
        assertTrue(activated.atomicSwitch)
        assertEquals("generation-2", activated.activeGenerationId)
        assertEquals(8L, activated.activeProjectionRevision)
        assertNotEquals(staged.digest, activated.digest)
    }

    @Test
    fun `tombstone and authorization refresh are revision bound and return convergence evidence`() {
        val fixture = extractionFixture()
        val descriptor = indexDescriptor()
        val tombstone = RetrievalIndexMutationRequest.of(
            Identifier("delete-1"),
            descriptor,
            fixture.source,
            "generation-2",
            8L,
            RetrievalIndexMutationKind.TOMBSTONE,
            "policy-10",
            DIGEST_E,
            "document-offline",
            100L,
            200L,
        )
        assertFailsWith<IllegalArgumentException> {
            RetrievalIndexMutationReceipt.applied(tombstone, 7L, 2, DIGEST_F, "delete-provider", 110L)
        }
        assertFailsWith<IllegalArgumentException> {
            RetrievalIndexMutationReceipt.applied(tombstone, 8L, 0, DIGEST_F, "delete-provider", 110L)
        }
        val receipt = RetrievalIndexMutationReceipt.applied(
            tombstone,
            8L,
            2,
            DIGEST_F,
            "delete-provider",
            110L,
        )
        assertSame(RetrievalIndexMutationKind.TOMBSTONE, receipt.kind)
        assertEquals(9L, receipt.activeProjectionRevision)

        val stateRequest = RetrievalIndexStateRequest.of(
            Identifier("state-1"),
            descriptor,
            fixture.source,
            120L,
            200L,
        )
        val state = RetrievalIndexState.observed(stateRequest, 9L, 130L, tombstoned = true)
        assertTrue(state.tombstoned)
        assertEquals(null, state.activeGenerationId)
        assertFailsWith<IllegalArgumentException> {
            RetrievalIndexState.observed(
                stateRequest,
                9L,
                130L,
                "generation-2",
                tombstoned = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RetrievalIndexState.observed(
                stateRequest,
                9L,
                130L,
                "generation-2",
            )
        }

        val authorizationRefresh = RetrievalIndexMutationRequest.of(
            Identifier("acl-refresh-1"),
            descriptor,
            fixture.source,
            "generation-2",
            8L,
            RetrievalIndexMutationKind.AUTHORIZATION_REFRESH,
            "policy-11",
            DIGEST_F,
            "acl-revision-changed",
            100L,
            200L,
        )
        val authorizationReceipt = RetrievalIndexMutationReceipt.applied(
            authorizationRefresh,
            8L,
            2,
            DIGEST_E,
            "acl-provider",
            115L,
        )
        assertSame(RetrievalIndexMutationKind.AUTHORIZATION_REFRESH, authorizationReceipt.kind)
    }

    private fun extractionFixture(supportsPageNumbers: Boolean = false): ExtractionFixture {
        val text = "合同正文天津"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val sha = sha256Spi(bytes)
        val source = ContentSourceRef.of(
            Identifier("tenant-tianjin"),
            Identifier("catalog-legal"),
            Identifier("document-1"),
            Identifier("version-2"),
            Identifier("asset-2"),
            Identifier("object-2"),
            sha,
            bytes.size.toLong(),
            "text/plain",
            "revision-2",
        )
        val descriptor = ContentExtractorDescriptor.of(
            "plain-text",
            "extractor-local",
            "capability-1",
            "parser-1",
            listOf("text/plain"),
            1_000_000L,
            10_000,
            supportsPageNumbers,
            false,
        )
        val binarySource = ContentBinarySource { requested, _ ->
            CompletableFuture.completedFuture(
                ContentInputHandle.of(
                    ByteArrayInputStream(bytes),
                    requested.digest,
                    requested.sourceSha256,
                    requested.sourceSizeBytes,
                ),
            )
        }
        val request = ContentExtractionRequest.of(
            Identifier("extract-1"),
            source,
            descriptor,
            DIGEST_C,
            1_000,
            10L,
            100L,
            binarySource,
        )
        return ExtractionFixture(text, bytes, sha, source, descriptor, request)
    }

    private fun extraction(fixture: ExtractionFixture): ContentExtractionResult = ContentExtractionResult.success(
        fixture.request,
        listOf(ExtractedContentSegment.of(0, fixture.text, "text/plain")),
        "extract-provider-request",
        20L,
    )

    private fun chunkingFixture(): ContentChunkingResult {
        val fixture = extractionFixture()
        return chunking(extraction(fixture), fixture.text, fixture.sha)
    }

    private fun chunking(
        extraction: ContentExtractionResult,
        text: String,
        sourceSha256: String,
    ): ContentChunkingResult {
        val request = ContentChunkingRequest.of(
            Identifier("chunk-index"),
            extraction,
            chunkerDescriptor(),
            DIGEST_D,
            8,
            8,
            2,
            30L,
            100L,
        )
        val size = text.codePointCount(0, text.length)
        val firstEnd = minOf(4, size)
        val chunks = if (firstEnd == size) {
            listOf(ContentChunk.of(0, 0, 0, size, text, "text/plain", sourceSha256))
        } else {
            listOf(
                ContentChunk.of(0, 0, 0, firstEnd, codePointSlice(text, 0, firstEnd), "text/plain", sourceSha256),
                ContentChunk.of(1, 0, firstEnd - 1, size, codePointSlice(text, firstEnd - 1, size), "text/plain", sourceSha256),
            )
        }
        return ContentChunkingResult.success(request, chunks, 40L)
    }

    private fun chunkerDescriptor(): ContentChunkerDescriptor = ContentChunkerDescriptor.of(
        "window-chunker",
        "chunker-local",
        "capability-1",
        "chunker-1",
        10_000,
        100,
        100,
        16,
    )

    private fun embeddingDescriptor(): EmbeddingProviderDescriptor = EmbeddingProviderDescriptor.of(
        "embedding-test",
        "embedding-local",
        "capability-1",
        "model-test",
        "model-v1",
        3,
        32,
        100,
        EmbeddingSimilarity.COSINE,
        false,
    )

    private fun indexDescriptor(
        supportsText: Boolean = true,
        supportsVectors: Boolean = true,
    ): RetrievalIndexProviderDescriptor = RetrievalIndexProviderDescriptor.of(
        "index-test",
        "index-local",
        "capability-1",
        "schema-1",
        100,
        supportsText,
        supportsVectors,
        true,
        true,
        true,
    )

    private fun candidateDescriptor(vararg modes: RetrievalMode): CandidateRetrieverDescriptor {
        val builder = CandidateRetrieverDescriptor.builder(
            "test-index",
            "retrieval-provider",
            DIGEST_C,
            DIGEST_D,
            "capability-1",
        )
            .tenantConstraint("tenant_id", "tenant-capability-1")
            .supportAccessProfile(RetrievalAccessProfile.AUTHORIZED_ID_SET)
            .limits(100, 1_000)
            .queryEgress(false)
            .cancellation(true)
            .tenantAndAccessPreselectionGuaranteed(true)
        modes.forEach(builder::supportMode)
        return builder.build()
    }

    private class ExtractionFixture(
        val text: String,
        val bytes: ByteArray,
        val sha: String,
        val source: ContentSourceRef,
        val descriptor: ContentExtractorDescriptor,
        val request: ContentExtractionRequest,
    )

    private companion object {
        const val DIGEST_B = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        const val DIGEST_C = "1111111111111111111111111111111111111111111111111111111111111111"
        const val DIGEST_D = "2222222222222222222222222222222222222222222222222222222222222222"
        const val DIGEST_E = "3333333333333333333333333333333333333333333333333333333333333333"
        const val DIGEST_F = "4444444444444444444444444444444444444444444444444444444444444444"
    }
}
