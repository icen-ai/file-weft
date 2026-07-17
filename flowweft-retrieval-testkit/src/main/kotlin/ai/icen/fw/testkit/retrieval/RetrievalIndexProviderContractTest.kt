package ai.icen.fw.testkit.retrieval

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.RetrievalFailureCode
import ai.icen.fw.retrieval.api.RetrievalProviderException
import ai.icen.fw.retrieval.api.RetrievalRetryability
import ai.icen.fw.retrieval.spi.RetrievalIndexActivationReceipt
import ai.icen.fw.retrieval.spi.RetrievalIndexActivationRequest
import ai.icen.fw.retrieval.spi.RetrievalIndexGenerationFailureEvidence
import ai.icen.fw.retrieval.spi.RetrievalIndexGenerationManifest
import ai.icen.fw.retrieval.spi.RetrievalIndexMutationRequest
import ai.icen.fw.retrieval.spi.RetrievalIndexProvider
import ai.icen.fw.retrieval.spi.RetrievalIndexProviderDescriptor
import ai.icen.fw.retrieval.spi.RetrievalIndexSealRequest
import ai.icen.fw.retrieval.spi.RetrievalIndexStageBatch
import ai.icen.fw.retrieval.spi.RetrievalIndexState
import ai.icen.fw.retrieval.spi.RetrievalIndexStateRequest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Executable durability contract for a [RetrievalIndexProvider].
 *
 * Every hook must prepare an isolated source/generation and any predecessor stage or seal state it
 * needs. Tests do not rely on JUnit execution order. Race and replay hooks must target durable state,
 * not a receipt-only mock, because this contract observes the authoritative projection afterwards.
 */
abstract class RetrievalIndexProviderContractTest {
    protected abstract val indexProvider: RetrievalIndexProvider

    protected abstract fun stageRequest(descriptor: RetrievalIndexProviderDescriptor): RetrievalIndexStageBatch

    protected abstract fun sealRequest(descriptor: RetrievalIndexProviderDescriptor): RetrievalIndexSealRequest

    protected abstract fun activationRequest(
        descriptor: RetrievalIndexProviderDescriptor,
    ): RetrievalIndexActivationRequest

    protected abstract fun mutationRequest(descriptor: RetrievalIndexProviderDescriptor): RetrievalIndexMutationRequest

    protected abstract fun stateRequest(descriptor: RetrievalIndexProviderDescriptor): RetrievalIndexStateRequest

    protected abstract fun activationRaceScenario(
        descriptor: RetrievalIndexProviderDescriptor,
        contenderCount: Int,
    ): RetrievalIndexActivationRaceScenario

    protected abstract fun activationReplayScenario(
        descriptor: RetrievalIndexProviderDescriptor,
        replayCount: Int,
    ): RetrievalIndexActivationReplayScenario

    protected abstract fun activationReplayMismatchScenario(
        descriptor: RetrievalIndexProviderDescriptor,
    ): RetrievalIndexActivationReplayMismatchScenario

    protected abstract fun providerBindingMismatchScenario(
        descriptor: RetrievalIndexProviderDescriptor,
    ): RetrievalIndexProviderBindingMismatchScenario

    protected abstract fun activationFailureScenario(
        descriptor: RetrievalIndexProviderDescriptor,
    ): RetrievalIndexActivationFailureScenario

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(30)

    protected open fun activationContenderCount(): Int = 8

    protected open fun activationReplayCount(): Int = 8

    @Test
    fun `keeps the complete index capability descriptor stable`() {
        val first = indexProvider.descriptor()
        val second = indexProvider.descriptor()
        assertEquals(first.providerId, second.providerId)
        assertEquals(first.providerInstanceId, second.providerInstanceId)
        assertEquals(first.providerRevision, second.providerRevision)
        assertEquals(first.indexSchemaRevision, second.indexSchemaRevision)
        assertEquals(first.maximumStageBatchSize, second.maximumStageBatchSize)
        assertEquals(first.supportsText, second.supportsText)
        assertEquals(first.supportsVectors, second.supportsVectors)
        assertEquals(first.atomicGenerationActivation, second.atomicGenerationActivation)
        assertEquals(first.supportsTombstones, second.supportsTombstones)
        assertEquals(first.supportsAuthorizationRefresh, second.supportsAuthorizationRefresh)
        assertEquals(first.digest, second.digest, "Index provider descriptor digest must be stable.")
    }

    @Test
    fun `stages an exact manifest slice without making it queryable`() {
        val descriptor = indexProvider.descriptor()
        val request = stageRequest(descriptor)
        assertEquals(descriptor.digest, request.manifest.descriptor.digest)
        val baseline = awaitState(
            visibilityStateRequest(
                "stage-before",
                request.manifest,
                request.requestedAtEpochMilli,
                request.deadlineEpochMilli,
            ),
            "Retrieval index stage baseline",
        )
        val receipt = RetrievalContractAssertions.awaitStage(
            indexProvider.stage(request),
            asynchronousTimeout(),
            "Retrieval index stage completion",
        )
        assertEquals(request.requestId, receipt.requestId)
        assertEquals(request.digest, receipt.requestDigest)
        assertEquals(request.manifest.digest, receipt.manifestDigest)
        assertEquals(descriptor.digest, receipt.descriptorDigest)
        assertEquals(request.recordManifestDigest, receipt.recordManifestDigest)
        assertEquals(request.batchOrdinal, receipt.batchOrdinal)
        assertFalse(receipt.visibleToQueries, "Staged records must remain invisible.")
        val observed = awaitState(
            visibilityStateRequest(
                "stage-after",
                request.manifest,
                request.requestedAtEpochMilli,
                request.deadlineEpochMilli,
            ),
            "Retrieval index stage result",
        )
        assertSameProjection(baseline, observed)
    }

    @Test
    fun `seals one complete generation without switching the read projection`() {
        val descriptor = indexProvider.descriptor()
        val request = sealRequest(descriptor)
        assertEquals(descriptor.digest, request.manifest.descriptor.digest)
        val baseline = awaitState(
            visibilityStateRequest(
                "seal-before",
                request.manifest,
                request.requestedAtEpochMilli,
                request.deadlineEpochMilli,
            ),
            "Retrieval index seal baseline",
        )
        val receipt = RetrievalContractAssertions.awaitStage(
            indexProvider.seal(request),
            asynchronousTimeout(),
            "Retrieval index seal completion",
        )
        assertEquals(request.digest, receipt.request.digest)
        assertFalse(receipt.visibleToQueries, "Sealing must not switch the read generation.")
        val observed = awaitState(
            visibilityStateRequest(
                "seal-after",
                request.manifest,
                request.requestedAtEpochMilli,
                request.deadlineEpochMilli,
            ),
            "Retrieval index seal result",
        )
        assertSameProjection(baseline, observed)
    }

    @Test
    fun `activates one sealed generation with the requested compare-and-set baseline`() {
        val descriptor = indexProvider.descriptor()
        val request = activationRequest(descriptor)
        val receipt = RetrievalContractAssertions.awaitStage(
            indexProvider.activate(request),
            asynchronousTimeout(),
            "Retrieval index activation completion",
        )
        assertActivationReceipt(request, receipt)
    }

    @Test
    fun `applies a bound convergent projection mutation`() {
        val descriptor = indexProvider.descriptor()
        val request = mutationRequest(descriptor)
        val receipt = RetrievalContractAssertions.awaitStage(
            indexProvider.mutate(request),
            asynchronousTimeout(),
            "Retrieval index mutation completion",
        )
        assertEquals(request.requestId, receipt.requestId)
        assertEquals(request.digest, receipt.requestDigest)
        assertEquals(request.kind, receipt.kind)
        assertEquals(request.activeGenerationId, receipt.generationId)
        assertEquals(request.expectedProjectionRevision, receipt.previousProjectionRevision)
        assertEquals(request.expectedProjectionRevision + 1L, receipt.activeProjectionRevision)
        assertTrue(receipt.affectedRecordCount > 0)
    }

    @Test
    fun `observes state bound to the exact source and provider descriptor`() {
        val descriptor = indexProvider.descriptor()
        val request = stateRequest(descriptor)
        val state = awaitState(request, "Retrieval index state completion")
        assertEquals(request.requestId, state.requestId)
        assertEquals(request.digest, state.requestDigest)
        assertEquals(descriptor.digest, state.descriptorDigest)
        assertEquals(request.source.digest, state.sourceDigest)
    }

    @Test
    fun `linearizes one winner across eight competing activation requests`() {
        val expectedCount = activationContenderCount()
        require(expectedCount >= 2) { "Activation contender count must be at least two." }
        val scenario = activationRaceScenario(indexProvider.descriptor(), expectedCount)
        assertEquals(expectedCount, scenario.activationRequests.size)
        val baseline = awaitState(scenario.baselineStateRequest, "Index activation race baseline")
        val first = scenario.activationRequests.first()
        assertEquals(first.expectedPreviousGenerationId, baseline.activeGenerationId)
        assertEquals(first.expectedProjectionRevision, baseline.projectionRevision)

        val outcomes = activateConcurrently(scenario.activationRequests, "Index activation race")
        val successes = outcomes.mapNotNull { outcome -> outcome.receipt }
        val failures = outcomes.mapNotNull { outcome -> outcome.failure }
        assertEquals(1, successes.size, "Exactly one activation contender must win the projection CAS.")
        assertEquals(expectedCount - 1, failures.size)
        failures.forEach { failure ->
            assertEquals(RetrievalFailureCode.INDEX_PROJECTION_CONFLICT, failure.code)
            assertEquals(RetrievalRetryability.NOT_RETRYABLE, failure.retryability)
        }

        val winner = successes.single()
        val winnerRequest = scenario.activationRequests.single { request -> request.digest == winner.requestDigest }
        assertActivationReceipt(winnerRequest, winner)
        val observed = awaitState(scenario.observedStateRequest, "Index activation race result")
        assertEquals(winner.activeGenerationId, observed.activeGenerationId)
        assertEquals(baseline.projectionRevision + 1L, observed.projectionRevision)
        assertEquals(winnerRequest.sealReceipt.request.manifest.authorizationPolicyRevision, observed.authorizationPolicyRevision)
        assertEquals(winnerRequest.sealReceipt.request.manifest.authorizationScopeDigest, observed.authorizationScopeDigest)
        assertFalse(observed.tombstoned)
    }

    @Test
    fun `returns one canonical receipt for eight concurrent exact activation replays`() {
        val expectedCount = activationReplayCount()
        require(expectedCount >= 2) { "Activation replay count must be at least two." }
        val scenario = activationReplayScenario(indexProvider.descriptor(), expectedCount)
        assertEquals(expectedCount, scenario.replayCount)
        val baseline = awaitState(scenario.baselineStateRequest, "Index activation replay baseline")
        val outcomes = activateConcurrently(
            List(scenario.replayCount) { scenario.activationRequest },
            "Index activation replay",
        )
        assertTrue(outcomes.all { outcome -> outcome.failure == null }, "Exact activation replays must all succeed.")
        val receipts = outcomes.map { outcome -> requireNotNull(outcome.receipt) }
        receipts.forEach { receipt -> assertActivationReceipt(scenario.activationRequest, receipt) }

        val observed = awaitState(scenario.observedStateRequest, "Index activation replay result")
        val canonical = receipts.first()
        receipts.drop(1).forEach { receipt -> assertSameActivationReceipt(canonical, receipt) }
        assertEquals(canonical.activeGenerationId, observed.activeGenerationId)
        assertEquals(baseline.projectionRevision + 1L, observed.projectionRevision)
    }

    @Test
    fun `rejects a request id replayed with another digest before projection conflict handling`() {
        val scenario = activationReplayMismatchScenario(indexProvider.descriptor())
        val accepted = RetrievalContractAssertions.awaitStage(
            indexProvider.activate(scenario.acceptedRequest),
            asynchronousTimeout(),
            "Accepted index activation",
        )
        val baseline = awaitState(scenario.baselineStateRequest, "Index replay mismatch baseline")
        assertEquals(accepted.activeGenerationId, baseline.activeGenerationId)
        assertEquals(accepted.activeProjectionRevision, baseline.projectionRevision)
        val failure = providerFailure("Mismatched index activation replay") {
            indexProvider.activate(scenario.conflictingRequest)
        }
        assertEquals(RetrievalFailureCode.INDEX_REQUEST_REPLAY_MISMATCH, failure.code)
        assertEquals(RetrievalRetryability.NOT_RETRYABLE, failure.retryability)

        val observed = awaitState(scenario.observedStateRequest, "Index replay mismatch result")
        assertSameProjection(baseline, observed)
    }

    @Test
    fun `rejects every foreign provider binding before stage seal or activation side effects`() {
        val scenario = providerBindingMismatchScenario(indexProvider.descriptor())
        val baseline = awaitState(scenario.baselineStateRequest, "Index provider binding baseline")
        listOf(
            providerFailure("Foreign index stage") { indexProvider.stage(scenario.foreignStageRequest) },
            providerFailure("Foreign index seal") { indexProvider.seal(scenario.foreignSealRequest) },
            providerFailure("Foreign index activation") { indexProvider.activate(scenario.foreignActivationRequest) },
        ).forEach { failure ->
            assertEquals(RetrievalFailureCode.INDEX_PROVIDER_BINDING_MISMATCH, failure.code)
            assertEquals(RetrievalRetryability.NOT_RETRYABLE, failure.retryability)
        }
        val observed = awaitState(scenario.observedStateRequest, "Index provider binding result")
        assertSameProjection(baseline, observed)
    }

    @Test
    fun `proves a temporary activation failure preserves the complete active projection`() {
        val scenario = activationFailureScenario(indexProvider.descriptor())
        val baseline = awaitState(scenario.baselineStateRequest, "Index activation failure baseline")
        val failure = providerFailure("Fault-injected index activation") {
            indexProvider.activate(scenario.activationRequest)
        }
        assertEquals(RetrievalFailureCode.TEMPORARY_UNAVAILABLE, failure.code)
        assertEquals(RetrievalRetryability.RETRYABLE, failure.retryability)
        val observed = awaitState(scenario.observedStateRequest, "Index activation failure result")
        assertSameProjection(baseline, observed)

        val evidence = RetrievalIndexGenerationFailureEvidence.afterActivationFailure(
            scenario.activationRequest,
            baseline,
            observed,
            failure.code,
            failure.retryability,
        )
        assertEquals(failure.code, evidence.failureCode)
        assertEquals(failure.retryability, evidence.retryability)
        assertTrue(evidence.retryable)
    }

    private fun activateConcurrently(
        requests: List<RetrievalIndexActivationRequest>,
        stageName: String,
    ): List<ActivationOutcome> {
        val timeout = asynchronousTimeout()
        val timeoutMillis = RetrievalContractAssertions.timeoutMillis(timeout, "$stageName timeout")
        val ready = CountDownLatch(requests.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(requests.size)
        val futures = requests.mapIndexed { index, request ->
            CompletableFuture.supplyAsync(
                {
                    ready.countDown()
                    check(start.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                        "$stageName contender $index did not receive the start signal."
                    }
                    val stage = try {
                        indexProvider.activate(request)
                    } catch (failure: Throwable) {
                        throw AssertionError(
                            "$stageName contender $index threw before returning a CompletionStage.",
                            failure,
                        )
                    }
                    try {
                        ActivationOutcome(
                            RetrievalContractAssertions.awaitStage(
                                stage,
                                timeout,
                                "$stageName contender $index",
                            ),
                            null,
                        )
                    } catch (failure: Throwable) {
                        ActivationOutcome(
                            null,
                            RetrievalContractAssertions.requireProviderFailure(
                                failure,
                                "$stageName contender $index",
                            ),
                        )
                    }
                },
                executor,
            )
        }
        return try {
            val allReady = ready.await(timeoutMillis, TimeUnit.MILLISECONDS)
            start.countDown()
            assertTrue(allReady, "$stageName contenders did not become ready in time.")
            futures.mapIndexed { index, future ->
                RetrievalContractAssertions.awaitStage(future, timeout, "$stageName outcome $index")
            }
        } finally {
            start.countDown()
            executor.shutdownNow()
            executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
        }
    }

    private fun providerFailure(
        stageName: String,
        operation: () -> CompletionStage<*>,
    ): RetrievalProviderException {
        val stage = try {
            operation()
        } catch (failure: Throwable) {
            throw AssertionError("$stageName threw before returning a CompletionStage.", failure)
        }
        return RetrievalContractAssertions.awaitProviderFailure(stage, asynchronousTimeout(), stageName)
    }

    private fun awaitState(request: RetrievalIndexStateRequest, stageName: String): RetrievalIndexState =
        RetrievalContractAssertions.awaitStage(indexProvider.state(request), asynchronousTimeout(), stageName)

    private fun assertActivationReceipt(
        request: RetrievalIndexActivationRequest,
        receipt: RetrievalIndexActivationReceipt,
    ) {
        assertEquals(request.requestId, receipt.requestId)
        assertEquals(request.digest, receipt.requestDigest)
        assertEquals(request.expectedPreviousGenerationId, receipt.previousGenerationId)
        assertEquals(request.sealReceipt.request.manifest.generationId, receipt.activeGenerationId)
        assertEquals(request.expectedProjectionRevision, receipt.previousProjectionRevision)
        assertEquals(request.expectedProjectionRevision + 1L, receipt.activeProjectionRevision)
        assertTrue(receipt.atomicSwitch)
    }

    private fun assertSameActivationReceipt(
        expected: RetrievalIndexActivationReceipt,
        actual: RetrievalIndexActivationReceipt,
    ) {
        assertEquals(expected.requestId, actual.requestId)
        assertEquals(expected.requestDigest, actual.requestDigest)
        assertEquals(expected.previousGenerationId, actual.previousGenerationId)
        assertEquals(expected.activeGenerationId, actual.activeGenerationId)
        assertEquals(expected.previousProjectionRevision, actual.previousProjectionRevision)
        assertEquals(expected.activeProjectionRevision, actual.activeProjectionRevision)
        assertEquals(expected.providerRequestId, actual.providerRequestId)
        assertEquals(expected.activatedAtEpochMilli, actual.activatedAtEpochMilli)
        assertEquals(expected.atomicSwitch, actual.atomicSwitch)
        assertEquals(expected.digest, actual.digest)
    }

    private fun visibilityStateRequest(
        label: String,
        manifest: RetrievalIndexGenerationManifest,
        requestedAtEpochMilli: Long,
        deadlineEpochMilli: Long,
    ): RetrievalIndexStateRequest = RetrievalIndexStateRequest.of(
        Identifier("index-contract-$label-${manifest.digest.take(24)}"),
        manifest.descriptor,
        manifest.source,
        requestedAtEpochMilli,
        deadlineEpochMilli,
    )

    private fun assertSameProjection(expected: RetrievalIndexState, actual: RetrievalIndexState) {
        assertEquals(expected.descriptorDigest, actual.descriptorDigest)
        assertEquals(expected.sourceDigest, actual.sourceDigest)
        assertEquals(expected.activeGenerationId, actual.activeGenerationId)
        assertEquals(expected.projectionRevision, actual.projectionRevision)
        assertEquals(expected.authorizationPolicyRevision, actual.authorizationPolicyRevision)
        assertEquals(expected.authorizationScopeDigest, actual.authorizationScopeDigest)
        assertEquals(expected.tombstoned, actual.tombstoned)
        assertNotEquals(expected.requestDigest, actual.requestDigest)
    }

    private class ActivationOutcome(
        val receipt: RetrievalIndexActivationReceipt?,
        val failure: RetrievalProviderException?,
    ) {
        init {
            require((receipt == null) != (failure == null)) { "Activation outcome must contain a receipt or a failure." }
        }
    }
}
