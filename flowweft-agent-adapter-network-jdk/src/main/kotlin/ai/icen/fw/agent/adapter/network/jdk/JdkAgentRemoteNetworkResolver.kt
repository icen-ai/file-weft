package ai.icen.fw.agent.adapter.network.jdk

import ai.icen.fw.agent.api.AgentRemoteNetworkResolution
import ai.icen.fw.agent.api.AgentRemoteNetworkResolutionRequest
import ai.icen.fw.agent.api.AgentRemoteNetworkResolver
import ai.icen.fw.agent.api.AgentRemoteResolvedAddress
import ai.icen.fw.agent.api.ProviderId
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Bounded JDK DNS adapter for the Agent MCP/A2A runtime.
 *
 * It returns every approved address as immutable bytes, rejects the entire answer when any record
 * is non-public, and never filters a mixed public/private answer into an apparently safe one. The
 * HTTP transport must continue to use the resolution's pinned address set; this adapter does not
 * authorize, connect, retry, log host names, or weaken TLS hostname verification.
 */
class JdkAgentRemoteNetworkResolver @JvmOverloads constructor(
    private val configuredProviderId: ProviderId,
    private val executor: ExecutorService,
    private val scheduler: ScheduledExecutorService,
    private val configuration: JdkAgentRemoteNetworkResolverConfiguration =
        JdkAgentRemoteNetworkResolverConfiguration(),
    private val lookup: AgentRemoteDnsLookup = AgentRemoteDnsLookup.SYSTEM,
    private val clock: JdkAgentRemoteNetworkClock = JdkAgentRemoteNetworkClock.SYSTEM,
    private val ids: JdkAgentRemoteNetworkIdSource = JdkAgentRemoteNetworkIdSource.RANDOM_UUID,
) : AgentRemoteNetworkResolver {

    override fun providerId(): ProviderId = configuredProviderId

    override fun resolve(
        request: AgentRemoteNetworkResolutionRequest,
    ): CompletionStage<AgentRemoteNetworkResolution> {
        val output = CompletableFuture<AgentRemoteNetworkResolution>()
        val startedAt = currentTimeOrFail(request, output) ?: return output
        val remainingMillis = request.deadlineAt - startedAt
        val timeoutMillis = minOf(configuration.lookupTimeoutMillis, remainingMillis)

        val worker = try {
            executor.submit {
                try {
                    val raw = lookup.resolve(request.targetUri.host)
                    val resolvedAt = clock.currentTimeMillis()
                    if (resolvedAt < startedAt || resolvedAt >= request.deadlineAt) {
                        throw resolutionFailure("network.resolution-deadline-exceeded")
                    }
                    val addresses = validate(raw)
                    val expiresAt = minOf(
                        request.deadlineAt,
                        safeAdd(resolvedAt, configuration.resolutionTtlMillis),
                    )
                    if (expiresAt <= resolvedAt) {
                        throw resolutionFailure("network.resolution-deadline-exceeded")
                    }
                    output.complete(
                        AgentRemoteNetworkResolution(
                            ids.nextId("agent-network-resolution"),
                            configuredProviderId,
                            request,
                            addresses,
                            resolvedAt,
                            expiresAt,
                        ),
                    )
                } catch (failure: JdkAgentRemoteNetworkResolutionException) {
                    output.completeExceptionally(failure)
                } catch (_: RuntimeException) {
                    output.completeExceptionally(resolutionFailure("network.resolution-unavailable"))
                } catch (_: Exception) {
                    output.completeExceptionally(resolutionFailure("network.resolution-unavailable"))
                }
            }
        } catch (_: RejectedExecutionException) {
            output.completeExceptionally(resolutionFailure("network.resolution-capacity-exhausted"))
            return output
        } catch (_: RuntimeException) {
            output.completeExceptionally(resolutionFailure("network.resolution-unavailable"))
            return output
        }

        val timeout = try {
            scheduler.schedule(
                {
                    if (output.completeExceptionally(resolutionFailure("network.resolution-timed-out"))) {
                        worker.cancel(true)
                    }
                },
                timeoutMillis,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RejectedExecutionException) {
            worker.cancel(true)
            output.completeExceptionally(resolutionFailure("network.resolution-capacity-exhausted"))
            return output
        } catch (_: RuntimeException) {
            worker.cancel(true)
            output.completeExceptionally(resolutionFailure("network.resolution-unavailable"))
            return output
        }
        output.whenComplete { _, _ -> timeout.cancel(false) }
        return output
    }

    private fun currentTimeOrFail(
        request: AgentRemoteNetworkResolutionRequest,
        output: CompletableFuture<AgentRemoteNetworkResolution>,
    ): Long? {
        val now = try {
            clock.currentTimeMillis()
        } catch (_: RuntimeException) {
            output.completeExceptionally(resolutionFailure("network.resolution-clock-unavailable"))
            return null
        }
        if (now < request.requestedAt || now >= request.deadlineAt) {
            output.completeExceptionally(resolutionFailure("network.resolution-deadline-exceeded"))
            return null
        }
        return now
    }

    private fun validate(raw: Collection<ByteArray>): List<AgentRemoteResolvedAddress> {
        if (raw.isEmpty() || raw.size > MAX_LOOKUP_RECORDS) {
            throw resolutionFailure("network.resolution-answer-size-invalid")
        }
        val unique = LinkedHashMap<String, AgentRemoteResolvedAddress>()
        raw.forEach { bytes ->
            val address = try {
                AgentRemoteResolvedAddress(bytes.copyOf())
            } catch (_: RuntimeException) {
                throw resolutionFailure("network.resolution-address-invalid")
            }
            if (!address.isPubliclyRoutable()) {
                throw resolutionFailure("network.resolution-non-public-address")
            }
            unique[address.addressDigest] = address
            if (unique.size > configuration.maximumAddresses) {
                throw resolutionFailure("network.resolution-answer-size-invalid")
            }
        }
        if (unique.isEmpty()) throw resolutionFailure("network.resolution-empty")
        return unique.values.sortedBy(AgentRemoteResolvedAddress::addressDigest)
    }

    private fun safeAdd(value: Long, increment: Long): Long =
        if (Long.MAX_VALUE - value < increment) Long.MAX_VALUE else value + increment

    private fun resolutionFailure(code: String): JdkAgentRemoteNetworkResolutionException =
        JdkAgentRemoteNetworkResolutionException(code)

    override fun toString(): String =
        "JdkAgentRemoteNetworkResolver(provider=$configuredProviderId, network=<redacted>)"

    private companion object {
        const val MAX_LOOKUP_RECORDS: Int = 64
    }
}
