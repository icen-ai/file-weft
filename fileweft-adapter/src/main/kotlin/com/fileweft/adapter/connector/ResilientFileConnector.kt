package com.fileweft.adapter.connector

import com.fileweft.spi.connector.ConnectorHealth
import com.fileweft.spi.connector.ConnectorHealthStatus
import com.fileweft.spi.connector.ConnectorRemoveRequest
import com.fileweft.spi.connector.ConnectorSyncRequest
import com.fileweft.spi.connector.ConnectorSyncResult
import com.fileweft.spi.connector.ConnectorSyncStatus
import com.fileweft.spi.connector.FileConnector
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** Global limits used to protect this process from slow or unavailable downstream systems. */
class ConnectorResiliencePolicy @JvmOverloads constructor(
    val timeout: Duration = Duration.ofSeconds(30),
    val failureThreshold: Int = 3,
    val circuitOpenDuration: Duration = Duration.ofSeconds(30),
) {
    init {
        require(!timeout.isNegative && !timeout.isZero && timeout.toMillis() > 0) {
            "Connector timeout must be at least one millisecond."
        }
        require(failureThreshold > 0) { "Connector failure threshold must be positive." }
        require(!circuitOpenDuration.isNegative && !circuitOpenDuration.isZero && circuitOpenDuration.toMillis() > 0) {
            "Connector circuit open duration must be at least one millisecond."
        }
    }
}

/**
 * Bounded shared executor for connector invocations. A cancelled timeout cannot
 * forcibly stop a connector that ignores interruption, so bounded concurrency
 * and queue capacity prevent a single downstream from exhausting application
 * threads while its circuit is open.
 */
class ConnectorInvocationExecutor @JvmOverloads constructor(
    maxConcurrentInvocations: Int = DEFAULT_MAX_CONCURRENT_INVOCATIONS,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    threadNamePrefix: String = DEFAULT_THREAD_NAME_PREFIX,
) : AutoCloseable {
    private val executor: ThreadPoolExecutor

    init {
        require(maxConcurrentInvocations > 0) { "Maximum connector concurrency must be positive." }
        require(queueCapacity > 0) { "Connector invocation queue capacity must be positive." }
        require(threadNamePrefix.isNotBlank()) { "Connector invocation thread name prefix must not be blank." }
        executor = ThreadPoolExecutor(
            maxConcurrentInvocations,
            maxConcurrentInvocations,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(queueCapacity),
            NamedDaemonThreadFactory(threadNamePrefix),
            ThreadPoolExecutor.AbortPolicy(),
        )
    }

    internal fun <T> invoke(timeout: Duration, operation: () -> T): InvocationAttempt<T> {
        val future: Future<T> = try {
            executor.submit(Callable { operation() })
        } catch (_: RejectedExecutionException) {
            return InvocationAttempt.rejected()
        }
        return try {
            InvocationAttempt.success(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS))
        } catch (_: TimeoutException) {
            future.cancel(true)
            InvocationAttempt.timedOut()
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            InvocationAttempt.interrupted()
        } catch (failure: ExecutionException) {
            InvocationAttempt.failed(failure.cause ?: failure)
        }
    }

    internal fun queuedInvocationCount(): Int = executor.queue.size

    override fun close() {
        executor.shutdownNow()
    }

    private class NamedDaemonThreadFactory(
        private val prefix: String,
    ) : ThreadFactory {
        private val sequence = AtomicInteger(1)

        override fun newThread(runnable: Runnable): Thread = Thread(runnable, "$prefix-${sequence.getAndIncrement()}").apply {
            isDaemon = true
        }
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT_INVOCATIONS = 16
        const val DEFAULT_QUEUE_CAPACITY = 256
        const val DEFAULT_THREAD_NAME_PREFIX = "fileweft-connector"
    }
}

/**
 * Decorates a connector with a process-local timeout and circuit breaker. The
 * caller's request timeout is honored when it is stricter than the configured
 * global limit. Only retryable outcomes affect the circuit; a permanent
 * validation error proves that the remote system was reachable.
 */
class ResilientFileConnector internal constructor(
    private val connectorId: String,
    private val delegate: FileConnector,
    private val policy: ConnectorResiliencePolicy,
    private val executor: ConnectorInvocationExecutor,
    private val clock: Clock,
) : FileConnector {
    private val monitor = Any()
    private var circuitState = CircuitState.CLOSED
    private var consecutiveFailures = 0
    private var reopenAfterMillis = 0L

    init {
        require(connectorId.isNotBlank()) { "Connector id must not be blank." }
    }

    override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult =
        invoke(request.invocation.timeout) { delegate.sync(request) }

    override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult =
        invoke(request.invocation.timeout) { delegate.remove(request) }

    override fun health(): ConnectorHealth {
        healthCircuitStatus()?.let { return it }
        val attempt = executor.invoke(policy.timeout) { delegate.health() }
        return when (attempt.kind) {
            InvocationAttempt.Kind.SUCCESS -> attempt.value ?: ConnectorHealth(
                ConnectorHealthStatus.UNHEALTHY,
                "Connector '$connectorId' health check returned no result.",
            )

            InvocationAttempt.Kind.TIMED_OUT -> ConnectorHealth(
                ConnectorHealthStatus.DEGRADED,
                "Connector '$connectorId' health check timed out after ${policy.timeout.toMillis()} ms.",
            )

            InvocationAttempt.Kind.REJECTED -> ConnectorHealth(
                ConnectorHealthStatus.DEGRADED,
                "Connector '$connectorId' health check could not start because the connector invocation pool is saturated.",
            )

            InvocationAttempt.Kind.INTERRUPTED -> ConnectorHealth(
                ConnectorHealthStatus.DEGRADED,
                "Connector '$connectorId' health check was interrupted.",
            )

            InvocationAttempt.Kind.FAILED -> ConnectorHealth(
                ConnectorHealthStatus.UNHEALTHY,
                "Connector '$connectorId' health check could not complete.",
            )
        }
    }

    internal fun wraps(candidate: FileConnector): Boolean = delegate === candidate

    private fun invoke(requestTimeout: Duration, operation: () -> ConnectorSyncResult): ConnectorSyncResult {
        activeCircuitStatus()?.let { status ->
            return ConnectorSyncResult(ConnectorSyncStatus.RETRYABLE_FAILURE, message = status.message)
        }
        if (!acquireCircuitPermission()) {
            return ConnectorSyncResult(ConnectorSyncStatus.RETRYABLE_FAILURE, message = openMessage())
        }
        val timeout = effectiveTimeout(requestTimeout)
        val attempt = executor.invoke(timeout, operation)
        return when (attempt.kind) {
            InvocationAttempt.Kind.SUCCESS -> attempt.value?.let(::handleResult)
                ?: failure("Connector '$connectorId' returned no synchronization result.")

            InvocationAttempt.Kind.TIMED_OUT -> failure("Connector '$connectorId' timed out after ${timeout.toMillis()} ms.")
            InvocationAttempt.Kind.REJECTED -> retryable(
                "Connector '$connectorId' could not start because the connector invocation pool is saturated.",
            )

            InvocationAttempt.Kind.INTERRUPTED -> retryable("Connector '$connectorId' invocation was interrupted.")
            InvocationAttempt.Kind.FAILED -> failure("Connector '$connectorId' invocation could not complete.")
        }
    }

    private fun handleResult(result: ConnectorSyncResult): ConnectorSyncResult {
        if (result.status == ConnectorSyncStatus.RETRYABLE_FAILURE) recordFailure() else recordSuccess()
        return result
    }

    private fun failure(message: String): ConnectorSyncResult {
        recordFailure()
        return retryable(message)
    }

    private fun retryable(message: String): ConnectorSyncResult {
        return ConnectorSyncResult(ConnectorSyncStatus.RETRYABLE_FAILURE, message = message)
    }

    private fun effectiveTimeout(requestTimeout: Duration): Duration =
        if (requestTimeout < policy.timeout) requestTimeout else policy.timeout

    /** A currently open circuit never reaches a connector or the executor. */
    private fun activeCircuitStatus(): ConnectorHealth? = synchronized(monitor) {
        if (circuitState == CircuitState.OPEN && clock.millis() < reopenAfterMillis) {
            ConnectorHealth(ConnectorHealthStatus.DEGRADED, openMessageLocked())
        } else {
            null
        }
    }

    private fun healthCircuitStatus(): ConnectorHealth? = synchronized(monitor) {
        if (circuitState != CircuitState.OPEN) return@synchronized null
        val now = clock.millis()
        val message = if (now < reopenAfterMillis) {
            openMessageLocked()
        } else {
            "Connector '$connectorId' circuit recovery probe is pending for the next delivery call."
        }
        ConnectorHealth(ConnectorHealthStatus.DEGRADED, message)
    }

    /** Allows one recovery probe after the open window; concurrent calls wait for its outcome. */
    private fun acquireCircuitPermission(): Boolean = synchronized(monitor) {
        val now = clock.millis()
        when (circuitState) {
            CircuitState.CLOSED -> true
            CircuitState.OPEN -> {
                if (now < reopenAfterMillis) {
                    false
                } else {
                    circuitState = CircuitState.HALF_OPEN
                    true
                }
            }

            CircuitState.HALF_OPEN -> false
        }
    }

    private fun recordSuccess() = synchronized(monitor) {
        when (circuitState) {
            CircuitState.CLOSED, CircuitState.HALF_OPEN -> {
                circuitState = CircuitState.CLOSED
                consecutiveFailures = 0
                reopenAfterMillis = 0L
            }

            CircuitState.OPEN -> Unit // A late result from an in-flight request must not close an open circuit.
        }
    }

    private fun recordFailure() = synchronized(monitor) {
        when (circuitState) {
            CircuitState.OPEN -> Unit // A late result cannot extend or replace the active recovery window.
            CircuitState.HALF_OPEN -> openCircuitLocked()
            CircuitState.CLOSED -> {
                consecutiveFailures++
                if (consecutiveFailures >= policy.failureThreshold) openCircuitLocked()
            }
        }
    }

    private fun openCircuitLocked() {
        circuitState = CircuitState.OPEN
        consecutiveFailures = 0
        reopenAfterMillis = clock.millis() + policy.circuitOpenDuration.toMillis()
    }

    private fun openMessage(): String = synchronized(monitor) { openMessageLocked() }

    private fun openMessageLocked(): String {
        val remaining = (reopenAfterMillis - clock.millis()).coerceAtLeast(0L)
        return "Connector '$connectorId' circuit is open; retry after $remaining ms."
    }

    private enum class CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN,
    }
}

/** Shares one circuit per connector identity across delivery, legacy sync, and Doctor checks. */
class ConnectorResilienceRegistry(
    private val policy: ConnectorResiliencePolicy,
    private val executor: ConnectorInvocationExecutor,
    private val clock: Clock,
) {
    private val monitor = Any()
    private val protectedConnectors = linkedMapOf<String, ResilientFileConnector>()

    fun protect(connectorId: String, connector: FileConnector): FileConnector {
        require(connectorId.isNotBlank()) { "Connector id must not be blank." }
        synchronized(monitor) {
            val current = protectedConnectors[connectorId]
            if (current == null) {
                protectedConnectors.values.firstOrNull { it.wraps(connector) }?.let { return it }
                return ResilientFileConnector(connectorId, connector, policy, executor, clock).also {
                    protectedConnectors[connectorId] = it
                }
            }
            require(current.wraps(connector)) {
                "Connector id '$connectorId' is already bound to a different FileConnector instance."
            }
            return current
        }
    }

    fun protectAll(connectors: Map<String, FileConnector>): Map<String, FileConnector> =
        connectors.mapValues { (connectorId, connector) -> protect(connectorId, connector) }
}

internal class InvocationAttempt<T> private constructor(
    val kind: Kind,
    val value: T? = null,
    val failure: Throwable? = null,
) {
    enum class Kind {
        SUCCESS,
        TIMED_OUT,
        REJECTED,
        INTERRUPTED,
        FAILED,
    }

    companion object {
        fun <T> success(value: T): InvocationAttempt<T> = InvocationAttempt(Kind.SUCCESS, value)
        fun <T> timedOut(): InvocationAttempt<T> = InvocationAttempt(Kind.TIMED_OUT)
        fun <T> rejected(): InvocationAttempt<T> = InvocationAttempt(Kind.REJECTED)
        fun <T> interrupted(): InvocationAttempt<T> = InvocationAttempt(Kind.INTERRUPTED)
        fun <T> failed(cause: Throwable): InvocationAttempt<T> = InvocationAttempt(Kind.FAILED, failure = cause)
    }
}
