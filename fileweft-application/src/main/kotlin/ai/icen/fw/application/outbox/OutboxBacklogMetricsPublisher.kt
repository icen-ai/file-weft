package ai.icen.fw.application.outbox

import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.spi.observability.FileWeftGauge
import ai.icen.fw.spi.observability.FileWeftGaugeRecorder
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Periodically projects aggregate outbox pressure to optional gauge metrics.
 *
 * The repository read is deliberately isolated in its own short transaction.
 * Gauge writes happen only after that transaction has completed, and every
 * observation failure is contained: an unavailable metrics backend must never
 * affect delivery acknowledgement or worker recovery.
 */
class OutboxBacklogMetricsPublisher @JvmOverloads constructor(
    private val transaction: ApplicationTransaction,
    private val reader: OutboxBacklogReader? = null,
    private val gauges: FileWeftGaugeRecorder? = null,
    private val clock: Clock = Clock.systemUTC(),
    samplingInterval: Duration = Duration.ofSeconds(30),
    legacyRunningGrace: Duration = Duration.ofMinutes(5),
) {
    private val samplingIntervalMillis = positiveDurationMillis(samplingInterval, "Outbox backlog sampling interval")
    private val legacyRunningGraceMillis = nonNegativeDurationMillis(
        legacyRunningGrace,
        "Outbox backlog legacy running grace",
    )

    /**
     * Stores the sampling timestamp, not the next timestamp, so the gate also
     * remains correct at [Long.MAX_VALUE] and when a clock moves backwards.
     */
    private val lastSamplingAttemptAt = AtomicLong(NO_SAMPLING_ATTEMPT)
    private val samplingInFlight = AtomicBoolean(false)

    /**
     * Publishes at most one aggregate snapshot per configured interval.
     *
     * `false` means that gauges are not configured, the interval has not
     * elapsed, or the read could not be completed. `true` means this caller
     * acquired a sampling window; individual gauge backend failures remain
     * intentionally invisible to worker processing.
     */
    fun publishIfDue(): Boolean {
        val request = reserveSampling() ?: return false
        return try {
            publish(request)
        } finally {
            samplingInFlight.set(false)
        }
    }

    /**
     * Reserves at most one observation window and dispatches its database read
     * to [executor]. Starter workers use a zero-queue executor so a slow
     * aggregate can never delay delivery polling or accumulate work.
     */
    fun publishIfDueAsync(executor: Executor): Boolean {
        val request = reserveSampling() ?: return false
        return try {
            executor.execute {
                try {
                    publish(request)
                } finally {
                    samplingInFlight.set(false)
                }
            }
            true
        } catch (_: Exception) {
            releaseSamplingWindow(request.reservation)
            samplingInFlight.set(false)
            // Do not invoke an arbitrary gauge adapter on the delivery worker
            // thread when its dedicated observation executor is unavailable.
            false
        }
    }

    private fun reserveSampling(): SamplingRequest? {
        val currentReader = reader ?: return null
        val currentGauges = gauges ?: return null
        val now = currentTimeOrNull() ?: run {
            return null
        }
        if (!samplingInFlight.compareAndSet(false, true)) return null
        val reservation = reserveSamplingWindow(now)
        if (reservation == null) {
            samplingInFlight.set(false)
            return null
        }
        return SamplingRequest(currentReader, currentGauges, now, reservation)
    }

    private fun publish(request: SamplingRequest): Boolean {
        val snapshot = try {
            transaction.execute {
                request.reader.snapshot(request.now, safeSubtract(request.now, legacyRunningGraceMillis))
            }
        } catch (_: Exception) {
            recordObservationFailure(request.gauges)
            return false
        }

        publish(request.gauges, FileWeftGauge.OUTBOX_BACKLOG, snapshot.readyCount.toDouble(), READY_TAGS)
        publish(request.gauges, FileWeftGauge.OUTBOX_BACKLOG, snapshot.delayedCount.toDouble(), DELAYED_TAGS)
        publish(request.gauges, FileWeftGauge.OUTBOX_BACKLOG, snapshot.runningCount.toDouble(), RUNNING_TAGS)
        publish(request.gauges, FileWeftGauge.OUTBOX_BACKLOG, snapshot.expiredCount.toDouble(), EXPIRED_TAGS)
        publish(request.gauges, FileWeftGauge.OUTBOX_BACKLOG, snapshot.failedCount.toDouble(), FAILED_TAGS)
        publish(
            request.gauges,
            FileWeftGauge.OUTBOX_OLDEST_READY_AGE_SECONDS,
            snapshot.oldestReadyAgeSeconds(request.now),
            emptyMap(),
        )
        publish(request.gauges, FileWeftGauge.OUTBOX_BACKLOG_OBSERVATION_FAILURE, 0.0, emptyMap())
        return true
    }

    private fun currentTimeOrNull(): Long? = try {
        clock.millis().takeIf { it >= 0 }
    } catch (_: Exception) {
        null
    }

    private fun reserveSamplingWindow(now: Long): SamplingReservation? {
        while (true) {
            val previous = lastSamplingAttemptAt.get()
            if (previous != NO_SAMPLING_ATTEMPT) {
                if (now < previous || now - previous < samplingIntervalMillis) return null
            }
            if (lastSamplingAttemptAt.compareAndSet(previous, now)) return SamplingReservation(previous, now)
        }
    }

    private fun releaseSamplingWindow(reservation: SamplingReservation) {
        lastSamplingAttemptAt.compareAndSet(reservation.current, reservation.previous)
    }

    private fun publish(
        recorder: FileWeftGaugeRecorder,
        gauge: FileWeftGauge,
        value: Double,
        tags: Map<String, String>,
    ) {
        try {
            recorder.set(gauge, value, tags)
        } catch (_: Exception) {
            // Observability cannot change durable outbox processing semantics.
        }
    }

    private fun safeSubtract(value: Long, decrement: Long): Long =
        if (value < decrement) 0 else value - decrement

    private fun recordObservationFailure(recorder: FileWeftGaugeRecorder) =
        publish(recorder, FileWeftGauge.OUTBOX_BACKLOG_OBSERVATION_FAILURE, 1.0, emptyMap())

    private class SamplingRequest(
        val reader: OutboxBacklogReader,
        val gauges: FileWeftGaugeRecorder,
        val now: Long,
        val reservation: SamplingReservation,
    )

    private class SamplingReservation(
        val previous: Long,
        val current: Long,
    )

    private companion object {
        const val NO_SAMPLING_ATTEMPT = Long.MIN_VALUE

        val READY_TAGS = mapOf("state" to "ready")
        val DELAYED_TAGS = mapOf("state" to "delayed")
        val RUNNING_TAGS = mapOf("state" to "running")
        val EXPIRED_TAGS = mapOf("state" to "expired")
        val FAILED_TAGS = mapOf("state" to "failed")

        fun positiveDurationMillis(duration: Duration, name: String): Long {
            require(!duration.isNegative && !duration.isZero) { "$name must be positive." }
            return try {
                duration.toMillis().also {
                    require(it > 0) { "$name must be at least one millisecond." }
                }
            } catch (failure: ArithmeticException) {
                throw IllegalArgumentException("$name is too large.", failure)
            }
        }

        fun nonNegativeDurationMillis(duration: Duration, name: String): Long {
            require(!duration.isNegative) { "$name must not be negative." }
            return try {
                duration.toMillis()
            } catch (failure: ArithmeticException) {
                throw IllegalArgumentException("$name is too large.", failure)
            }
        }
    }
}
