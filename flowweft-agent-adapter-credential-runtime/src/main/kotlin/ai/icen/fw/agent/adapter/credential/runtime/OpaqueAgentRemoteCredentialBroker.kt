package ai.icen.fw.agent.adapter.credential.runtime

import ai.icen.fw.agent.adapter.http.okhttp.AgentProtocolHttpCredentialMaterial
import ai.icen.fw.agent.adapter.http.okhttp.AgentProtocolHttpCredentialProvider
import ai.icen.fw.agent.adapter.http.okhttp.AgentProtocolHttpCredentialRequest
import ai.icen.fw.agent.api.AgentRemoteCredentialBroker
import ai.icen.fw.agent.api.AgentRemoteCredentialLease
import ai.icen.fw.agent.api.AgentRemoteCredentialLeaseRequest
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Co-located one-exchange credential broker.
 *
 * Only opaque, short-lived lease metadata is retained. Secret material stays in the host source,
 * is requested only after the exact lease is atomically consumed, and is transferred through the
 * existing erasable HTTP material type. A process crash invalidates unused leases by design; a
 * recovered dispatch obtains fresh authorization and a fresh lease rather than replaying secrets.
 */
class OpaqueAgentRemoteCredentialBroker @JvmOverloads constructor(
    private val configuredBrokerId: ProviderId,
    private val materialSource: AgentRemoteCredentialMaterialSource,
    private val scheduler: ScheduledExecutorService,
    private val configuration: AgentCredentialRuntimeConfiguration = AgentCredentialRuntimeConfiguration(),
    private val clock: AgentCredentialRuntimeClock = AgentCredentialRuntimeClock.SYSTEM,
    private val ids: AgentCredentialRuntimeIdSource = AgentCredentialRuntimeIdSource.RANDOM_UUID,
) : AgentRemoteCredentialBroker, AgentProtocolHttpCredentialProvider, AutoCloseable {
    private val lock = Any()
    private val byLeaseDigest = LinkedHashMap<String, LeaseEntry>()
    private val byRequestDigest = LinkedHashMap<String, LeaseEntry>()
    @Volatile private var closed: Boolean = false

    override fun brokerId(): ProviderId = configuredBrokerId

    override fun lease(
        request: AgentRemoteCredentialLeaseRequest,
    ): CompletionStage<AgentRemoteCredentialLease> {
        val output = CompletableFuture<AgentRemoteCredentialLease>()
        try {
            val now = currentTime()
            if (closed) throw failure("credential.broker-closed")
            if (now < request.requestedAt || now >= request.expiresAt) {
                throw failure("credential.lease-request-expired")
            }
            synchronized(lock) {
                if (closed) throw failure("credential.broker-closed")
                purgeExpired(now)
                byRequestDigest[request.bindingDigest]?.let { existing ->
                    if (now < existing.lease.expiresAt) {
                        output.complete(existing.lease)
                        return output
                    }
                }
                val tenantId = request.authorizationRequest.invocation.operation.context.tenantId
                if (byLeaseDigest.size >= configuration.maximumActiveLeases ||
                    byLeaseDigest.values.count { entry -> entry.tenantId == tenantId } >=
                    configuration.maximumActiveLeasesPerTenant
                ) throw failure("credential.lease-capacity-exhausted")
                val expiresAt = minOf(request.expiresAt, safeAdd(now, configuration.leaseTtlMillis))
                if (expiresAt <= now) throw failure("credential.lease-request-expired")
                val lease = AgentRemoteCredentialLease(
                    ids.nextId("agent-credential-lease"),
                    configuredBrokerId,
                    request,
                    request.profile.credential.credentialReference,
                    request.profile.credential.ownerPeerId,
                    request.profile.credential.protectedResourceAudience,
                    request.profile.credential.credentialRevision,
                    now,
                    expiresAt,
                )
                val entry = LeaseEntry(tenantId, request.bindingDigest, lease)
                if (byLeaseDigest.putIfAbsent(lease.bindingDigest, entry) != null) {
                    throw failure("credential.lease-identity-conflict")
                }
                byRequestDigest[request.bindingDigest] = entry
                output.complete(lease)
            }
        } catch (failure: AgentCredentialRuntimeException) {
            output.completeExceptionally(failure)
        } catch (_: RuntimeException) {
            output.completeExceptionally(failure("credential.lease-unavailable"))
        }
        return output
    }

    override fun acquire(
        request: AgentProtocolHttpCredentialRequest,
    ): CompletionStage<AgentProtocolHttpCredentialMaterial> {
        val output = CompletableFuture<AgentProtocolHttpCredentialMaterial>()
        val sourceRequest: AgentRemoteCredentialMaterialRequest
        try {
            val now = currentTime()
            val lease = request.dispatch.credentialLease
            if (closed || lease.brokerId != configuredBrokerId ||
                request.credentialLeaseBindingDigest != lease.bindingDigest
            ) throw failure("credential.lease-rejected")
            val entry = synchronized(lock) {
                purgeExpired(now)
                val current = byLeaseDigest[lease.bindingDigest]
                    ?: throw failure("credential.lease-missing-or-consumed")
                if (current.tenantId != request.dispatch.invocation.operation.context.tenantId ||
                    current.lease.requestId != lease.requestId || current.lease.bindingDigest != lease.bindingDigest
                ) throw failure("credential.lease-binding-mismatch")
                byLeaseDigest.remove(lease.bindingDigest)
                byRequestDigest.remove(current.requestDigest, current)
                current
            }
            entry.lease.requireCurrentFor(request.dispatch.credentialRequest, now)
            sourceRequest = AgentRemoteCredentialMaterialRequest.from(request, entry.lease)
        } catch (failure: AgentCredentialRuntimeException) {
            output.completeExceptionally(failure)
            return output
        } catch (_: RuntimeException) {
            output.completeExceptionally(failure("credential.material-request-rejected"))
            return output
        }

        val stage = try {
            requireNotNull(materialSource.acquire(sourceRequest))
        } catch (_: RuntimeException) {
            output.completeExceptionally(failure("credential.material-unavailable"))
            return output
        }
        val timeoutStartedAt = try {
            currentTime()
        } catch (_: RuntimeException) {
            cancel(stage)
            output.completeExceptionally(failure("credential.material-clock-unavailable"))
            return output
        }
        val timeoutMillis = minOf(
            configuration.materialTimeoutMillis,
            sourceRequest.expiresAt - timeoutStartedAt,
        )
        if (timeoutMillis <= 0L) {
            cancel(stage)
            output.completeExceptionally(failure("credential.material-request-expired"))
            return output
        }
        val completionClaimed = AtomicBoolean(false)
        val timeout = try {
            scheduler.schedule(
                {
                    if (completionClaimed.compareAndSet(false, true)) {
                        cancel(stage)
                        output.completeExceptionally(failure("credential.material-timed-out"))
                    }
                },
                timeoutMillis,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RejectedExecutionException) {
            cancel(stage)
            output.completeExceptionally(failure("credential.material-capacity-exhausted"))
            return output
        } catch (_: RuntimeException) {
            cancel(stage)
            output.completeExceptionally(failure("credential.material-unavailable"))
            return output
        }
        stage.whenComplete { result, sourceFailure ->
            if (!completionClaimed.compareAndSet(false, true)) {
                result?.material?.close()
                return@whenComplete
            }
            if (sourceFailure != null || result == null) {
                output.completeExceptionally(failure("credential.material-unavailable"))
            } else {
                val now = try {
                    currentTime()
                } catch (_: RuntimeException) {
                    result.material.close()
                    output.completeExceptionally(failure("credential.material-clock-unavailable"))
                    return@whenComplete
                }
                if (!result.matches(sourceRequest, now)) {
                    result.material.close()
                    output.completeExceptionally(failure("credential.material-binding-mismatch"))
                } else if (!output.complete(result.material)) {
                    result.material.close()
                }
            }
        }
        output.whenComplete { _, _ -> timeout.cancel(false) }
        return output
    }

    fun revoke(tenantId: Identifier, leaseId: Identifier): Boolean = synchronized(lock) {
        val entry = byLeaseDigest.values.firstOrNull { candidate ->
            candidate.tenantId == tenantId && candidate.lease.leaseId == leaseId
        } ?: return@synchronized false
        byLeaseDigest.remove(entry.lease.bindingDigest)
        byRequestDigest.remove(entry.requestDigest, entry)
        true
    }

    fun activeLeaseCount(): Int = synchronized(lock) {
        val now = try {
            currentTime()
        } catch (_: RuntimeException) {
            return@synchronized byLeaseDigest.size
        }
        purgeExpired(now)
        byLeaseDigest.size
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            byLeaseDigest.clear()
            byRequestDigest.clear()
        }
    }

    private fun purgeExpired(now: Long) {
        val expired = byLeaseDigest.values.filter { entry -> now >= entry.lease.expiresAt }
        expired.forEach { entry ->
            byLeaseDigest.remove(entry.lease.bindingDigest, entry)
            byRequestDigest.remove(entry.requestDigest, entry)
        }
    }

    private fun currentTime(): Long = clock.currentTimeMillis().also {
        if (it < 0L) throw failure("credential.clock-invalid")
    }

    private fun safeAdd(value: Long, increment: Long): Long =
        if (Long.MAX_VALUE - value < increment) Long.MAX_VALUE else value + increment

    private fun cancel(stage: CompletionStage<*>) {
        try {
            stage.toCompletableFuture().cancel(true)
        } catch (_: RuntimeException) {
            // Cancellation is advisory; the result callback still destroys late secret material.
        }
    }

    private fun failure(code: String): AgentCredentialRuntimeException = AgentCredentialRuntimeException(code)

    override fun toString(): String =
        "OpaqueAgentRemoteCredentialBroker(broker=$configuredBrokerId, credentials=<redacted>)"

    private class LeaseEntry(
        val tenantId: Identifier,
        val requestDigest: String,
        val lease: AgentRemoteCredentialLease,
    )
}
