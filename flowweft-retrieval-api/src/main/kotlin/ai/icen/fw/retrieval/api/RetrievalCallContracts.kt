package ai.icen.fw.retrieval.api

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.Function

/** A Java 8 compatible, cancellable asynchronous retrieval stage. */
interface RetrievalCall<T> {
    fun completion(): CompletionStage<T>
    fun cancel(reason: RetrievalCancellationReason): CompletionStage<RetrievalCancellationOutcome>
}

/** Java-friendly factories for adapters and small host bridges. */
object RetrievalCalls {
    @JvmStatic
    fun <T> completed(value: T): RetrievalCall<T> = object : RetrievalCall<T> {
        private val result = CompletableFuture.completedFuture(value)

        override fun completion(): CompletionStage<T> = result

        override fun cancel(reason: RetrievalCancellationReason): CompletionStage<RetrievalCancellationOutcome> =
            CompletableFuture.completedFuture(RetrievalCancellationOutcome.ALREADY_COMPLETED)
    }

    @JvmStatic
    fun <T> from(
        completion: CompletionStage<T>,
        cancellation: Function<RetrievalCancellationReason, CompletionStage<RetrievalCancellationOutcome>>,
    ): RetrievalCall<T> = object : RetrievalCall<T> {
        override fun completion(): CompletionStage<T> = completion

        override fun cancel(reason: RetrievalCancellationReason): CompletionStage<RetrievalCancellationOutcome> =
            requireNotNull(cancellation.apply(reason)) { "Retrieval cancellation returned no completion stage." }
    }
}

/**
 * Stable provider snapshot embedded in exact stage requests and responses.
 *
 * The descriptor digest covers the complete descriptor. Explicit instance, configuration,
 * capability and revision fields prevent treating that digest as a freely interchangeable token.
 */
class RetrievalStageProviderBinding private constructor(
    val stageId: String,
    val providerTypeId: String,
    val providerInstanceId: String,
    val configurationDigest: String,
    val capabilityDigest: String,
    val capabilityRevision: String,
    val descriptorDigest: String,
    val supportsCancellation: Boolean,
) {
    val digest: String

    init {
        listOf(stageId, providerTypeId, providerInstanceId, capabilityRevision).forEach { value ->
            requireRetrievalText(
                value,
                RetrievalContractLimits.MAX_ID_CODE_POINTS,
                "Retrieval stage provider binding text is invalid.",
            )
        }
        require(Regex("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$").matches(stageId)) {
            "Retrieval stage identifier is invalid."
        }
        listOf(configurationDigest, capabilityDigest, descriptorDigest).forEach { value ->
            requireDigest(value, "Retrieval stage provider binding digest is invalid.")
        }
        digest = retrievalDigest {
            text("flowweft-retrieval-stage-provider-binding-v1")
            text(stageId)
            text(providerTypeId)
            text(providerInstanceId)
            text(configurationDigest)
            text(capabilityDigest)
            text(capabilityRevision)
            text(descriptorDigest)
            boolean(supportsCancellation)
        }
    }

    override fun toString(): String =
        "RetrievalStageProviderBinding(stage=$stageId, providerType=$providerTypeId)"

    companion object {
        @JvmStatic
        fun create(
            stageId: String,
            providerTypeId: String,
            providerInstanceId: String,
            configurationDigest: String,
            capabilityDigest: String,
            capabilityRevision: String,
            descriptorDigest: String,
            supportsCancellation: Boolean,
        ): RetrievalStageProviderBinding = RetrievalStageProviderBinding(
            stageId,
            providerTypeId,
            providerInstanceId,
            configurationDigest,
            capabilityDigest,
            capabilityRevision,
            descriptorDigest,
            supportsCancellation,
        )
    }
}

internal fun <S, T> mapRetrievalCall(
    source: RetrievalCall<S>,
    transform: (S) -> T,
): RetrievalCall<T> {
    val sourceCompletion = requireNotNull(source.completion()) {
        "Retrieval provider returned no completion stage."
    }
    val mapped = sourceCompletion.thenApply { value -> transform(requireNotNull(value)) }
    return object : RetrievalCall<T> {
        override fun completion(): CompletionStage<T> = mapped

        override fun cancel(reason: RetrievalCancellationReason): CompletionStage<RetrievalCancellationOutcome> =
            requireNotNull(source.cancel(reason)) { "Retrieval cancellation returned no completion stage." }
    }
}
