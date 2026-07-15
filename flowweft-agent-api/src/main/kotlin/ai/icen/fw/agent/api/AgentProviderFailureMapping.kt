package ai.icen.fw.agent.api

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException

/** Stable, extensible operation identifier supplied to a provider failure mapper. */
class AgentProviderOperationId(value: String) {
    val value: String = requireAgentCode(value, "Agent provider operation identifier is invalid.")

    override fun equals(other: Any?): Boolean = other is AgentProviderOperationId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        @JvmField val AUTHORIZATION = AgentProviderOperationId("authorization")
        @JvmField val POLICY = AgentProviderOperationId("policy")
        @JvmField val MODEL = AgentProviderOperationId("model")
        @JvmField val TOOL = AgentProviderOperationId("tool")
        @JvmField val EVALUATION = AgentProviderOperationId("evaluation")
    }
}

/** Adapter-owned mapping boundary. Raw provider failures must never escape or be logged here. */
interface AgentProviderFailureMapper {
    fun map(providerId: ProviderId, operationId: AgentProviderOperationId, failure: Throwable): AgentProviderException

    companion object {
        @JvmField
        val SAFE_DEFAULT: AgentProviderFailureMapper = object : AgentProviderFailureMapper {
            override fun map(
                providerId: ProviderId,
                operationId: AgentProviderOperationId,
                failure: Throwable,
            ): AgentProviderException = AgentProviderException(
                providerId,
                AgentFailureCategory.PROTOCOL,
                "provider.operation-failed",
            )
        }
    }
}

/** Java-friendly synchronous provider call used by [AgentProviderFailures.invoke]. */
interface AgentProviderInvocation<T : Any> {
    fun invoke(): T
}

/**
 * Normalizes both synchronous throws and exceptional CompletionStage completion to safe failures.
 * It never copies a raw provider message, cause, response body, header, or credential.
 */
object AgentProviderFailures {
    @JvmStatic
    fun <T : Any> invoke(
        providerId: ProviderId,
        operationId: AgentProviderOperationId,
        mapper: AgentProviderFailureMapper,
        invocation: AgentProviderInvocation<T>,
    ): T = try {
        val value: T? = invocation.invoke()
        value ?: throw protocolFailure(providerId, "provider.null-result")
    } catch (failure: Exception) {
        throw normalize(providerId, operationId, mapper, failure)
    }

    @JvmStatic
    fun <T : Any> normalizeStage(
        providerId: ProviderId,
        operationId: AgentProviderOperationId,
        mapper: AgentProviderFailureMapper,
        stage: CompletionStage<T>,
    ): CompletionStage<T> {
        val result = CompletableFuture<T>()
        stage.whenComplete { value, failure ->
            if (failure != null) {
                val normalized = try {
                    normalize(providerId, operationId, mapper, failure)
                } catch (fatal: Error) {
                    fatal
                }
                result.completeExceptionally(normalized)
            } else if (value == null) {
                result.completeExceptionally(protocolFailure(providerId, "provider.null-result"))
            } else {
                result.complete(value)
            }
        }
        return result
    }

    @JvmStatic
    fun normalize(
        providerId: ProviderId,
        operationId: AgentProviderOperationId,
        mapper: AgentProviderFailureMapper,
        failure: Throwable,
    ): AgentProviderException {
        val unwrapped = unwrapCompletionFailure(failure)
        if (unwrapped is Error) throw unwrapped
        if (unwrapped is AgentProviderException && unwrapped.providerId == providerId) return unwrapped
        if (unwrapped is AgentCancellationException) {
            return AgentProviderException(
                providerId,
                AgentFailureCategory.CANCELLED,
                unwrapped.cancellation.reasonCode,
            )
        }
        if (unwrapped is CancellationException) {
            return AgentProviderException(providerId, AgentFailureCategory.CANCELLED, "provider.cancelled")
        }
        return try {
            mapper.map(providerId, operationId, unwrapped).let { mapped ->
                if (mapped.providerId == providerId) mapped else protocolFailure(providerId, "provider.mapper-mismatch")
            }
        } catch (_: Exception) {
            protocolFailure(providerId, "provider.mapper-failed")
        }
    }

    private fun unwrapCompletionFailure(failure: Throwable): Throwable {
        var current = failure
        var depth = 0
        while (depth < 8 && (current is CompletionException || current is ExecutionException)) {
            val cause = current.cause ?: break
            if (cause === current) break
            current = cause
            depth++
        }
        return current
    }

    private fun protocolFailure(providerId: ProviderId, code: String): AgentProviderException =
        AgentProviderException(providerId, AgentFailureCategory.PROTOCOL, code)
}
