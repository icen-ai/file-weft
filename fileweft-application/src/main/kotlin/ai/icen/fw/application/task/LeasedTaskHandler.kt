package ai.icen.fw.application.task

import ai.icen.fw.spi.task.FileWeftTaskHandler
import ai.icen.fw.spi.task.TaskHandlingResult

/**
 * Optional stronger handler contract for a local projection that must be
 * fenced by the exact persisted task lease which invoked it.
 *
 * Implementations retain the legacy [FileWeftTaskHandler] entry points for
 * already compiled callers. [TaskWorker] invokes these lease-aware overloads
 * whenever it owns a [BackgroundTaskLease], including a tokenless lease from
 * a legacy repository. The handler decides whether such a lease can be used;
 * the worker never invents ownership data.
 */
interface LeasedTaskHandler : FileWeftTaskHandler {
    fun handle(lease: BackgroundTaskLease): TaskHandlingResult

    /**
     * Invoked only after the worker has durably changed this exact lease's task
     * to FAILED. Implementations must still re-read and validate the terminal
     * task state before projecting local failure details.
     */
    fun onExhausted(lease: BackgroundTaskLease, message: String)
}
