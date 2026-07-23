package ai.icen.fw.application.doctor

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.doctor.DoctorChecker
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounds the wall-clock time one [DoctorChecker] may consume.
 *
 * The delegate runs on a dedicated bounded single-thread executor so a stalled
 * check cannot block the diagnosing thread forever. On timeout the running
 * task is interrupted, but a checker that ignores interruption cannot be
 * killed; that is why the executor is strictly bounded — one stuck checker
 * pins at most one daemon thread and a bounded queue, never the caller.
 *
 * The timeout result is a fixed, sanitized [DoctorCheckResult] without any
 * thread or stack detail; the owning doctor pipeline normalizes it like any
 * other checker result.
 */
class TimeoutDoctorChecker(
    private val delegate: DoctorChecker,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val executor: ExecutorService = newBoundedExecutor(),
) : DoctorChecker {
    init {
        require(timeoutMillis > 0) { "Doctor check timeout must be positive." }
    }

    override fun name(): String = delegate.name()

    override fun check(context: DoctorCheckContext): DoctorCheckResult {
        val future = try {
            executor.submit(Callable { delegate.check(context) })
        } catch (rejected: RejectedExecutionException) {
            // The bounded queue is full; fail the check instead of growing without bound.
            return timeoutResult()
        }
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            future.cancel(true)
            timeoutResult()
        } catch (interrupted: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while awaiting a doctor check result.", interrupted)
        } catch (failure: ExecutionException) {
            // Preserve the delegate failure so the pipeline classifies it like a direct call.
            throw failure.cause ?: failure
        }
    }

    private fun timeoutResult(): DoctorCheckResult = DoctorCheckResult(
        checkerName = name(),
        status = DoctorStatus.ERROR,
        reason = TIMEOUT_REASON,
        repairSuggestion = TIMEOUT_REPAIR_SUGGESTION,
    )

    companion object {
        /** Default per-check time budget applied when the wiring does not override it. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 5_000

        /** Fixed safe reason reported for checks that exceed their time budget. */
        const val TIMEOUT_REASON: String = "Check execution exceeded its time budget."

        private const val TIMEOUT_REPAIR_SUGGESTION: String =
            "Inspect the checker's dependencies for a stall, then run the diagnosis again."

        private const val MAX_QUEUED_CHECKS = 8
        private const val WORKER_KEEP_ALIVE_SECONDS = 60L
        private val THREAD_COUNTER = AtomicLong()

        private fun newBoundedExecutor(): ExecutorService = ThreadPoolExecutor(
            1,
            1,
            WORKER_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(MAX_QUEUED_CHECKS),
            ThreadFactory { task ->
                Thread(task, "fileweft-doctor-check-" + THREAD_COUNTER.incrementAndGet()).apply { isDaemon = true }
            },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }
}
