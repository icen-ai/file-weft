package ai.icen.fw.observability

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Production hard-deadline boundary. The host owns and shuts down the supplied
 * bounded executor; timed-out work is interrupted and no exception text exits.
 */
class ExecutorServiceSystemDoctorProbeExecutionPort(
    private val executor: ExecutorService,
) : SystemDoctorProbeExecutionPort {
    override fun execute(
        probe: SystemDoctorProbe,
        request: SystemDoctorProbeRequest,
        timeoutMillis: Long,
    ): SystemDoctorProbeExecution {
        require(timeoutMillis > 0L) { "System Doctor execution timeout is invalid." }
        val future = try {
            executor.submit<SystemDoctorProbeResult> { probe.inspect(request) }
        } catch (_: RejectedExecutionException) {
            return SystemDoctorProbeExecution.failed()
        } catch (_: RuntimeException) {
            return SystemDoctorProbeExecution.failed()
        }
        return try {
            val result = future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            if (result == null) SystemDoctorProbeExecution.failed() else SystemDoctorProbeExecution.completed(result)
        } catch (_: TimeoutException) {
            future.cancel(true)
            SystemDoctorProbeExecution.timedOut()
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            SystemDoctorProbeExecution.failed()
        } catch (_: ExecutionException) {
            SystemDoctorProbeExecution.failed()
        } catch (_: RuntimeException) {
            SystemDoctorProbeExecution.failed()
        }
    }
}
