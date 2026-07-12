package com.fileweft.application.task

import com.fileweft.core.id.Identifier
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TaskStateTest {
    @Test
    fun `accepts only the exact current running owner token and task identity`() {
        val lease = lease()
        val state = runningState()

        assertSame(state, state.requireCurrentLease(lease))

        listOf(
            lease(id = "task-2"),
            lease(tenantId = "tenant-2"),
            lease(type = "agent.analyze"),
            lease(businessId = "document-2"),
            lease(owner = "worker-b"),
            lease(token = "token-b"),
            lease(token = null),
        ).forEach { forged ->
            assertFailsWith<TaskLeaseLostException> { state.requireCurrentLease(forged) }
        }

        assertFailsWith<TaskLeaseLostException> {
            failedState().requireCurrentLease(lease)
        }
        assertFailsWith<TaskLeaseLostException> {
            TaskState(
                TASK_ID,
                TENANT_ID,
                TASK_TYPE,
                BackgroundTaskStatus.RUNNING,
                DOCUMENT_ID,
                "legacy-worker",
                null,
            ).requireCurrentLease(lease(owner = "legacy-worker", token = null))
        }
    }

    @Test
    fun `matches terminal projection only for the exact failed task after ownership is cleared`() {
        val lease = lease()

        assertTrue(failedState().matchesFailedTask(lease))
        assertFalse(runningState().matchesFailedTask(lease))
        assertFalse(failedState(id = "task-2").matchesFailedTask(lease))
        assertFalse(failedState(type = "agent.analyze").matchesFailedTask(lease))
        assertFalse(failedState(businessId = "document-2").matchesFailedTask(lease))
        assertFalse(failedState(tenantId = "tenant-2").matchesFailedTask(lease))
        assertFalse(failedState().matchesFailedTask(lease(token = null)))
    }

    @Test
    fun `rejects malformed task state ownership`() {
        assertFailsWith<IllegalArgumentException> {
            TaskState(TASK_ID, TENANT_ID, " ", BackgroundTaskStatus.RUNNING)
        }
        assertFailsWith<IllegalArgumentException> {
            TaskState(TASK_ID, TENANT_ID, TASK_TYPE, BackgroundTaskStatus.RUNNING, DOCUMENT_ID, " ", null)
        }
        assertFailsWith<IllegalArgumentException> {
            TaskState(TASK_ID, TENANT_ID, TASK_TYPE, BackgroundTaskStatus.RUNNING, DOCUMENT_ID, null, "token-a")
        }
        assertFailsWith<IllegalArgumentException> {
            TaskState(TASK_ID, TENANT_ID, TASK_TYPE, BackgroundTaskStatus.FAILED, DOCUMENT_ID, "worker-a", "token-a")
        }
    }

    private fun runningState(): TaskState = TaskState(
        TASK_ID,
        TENANT_ID,
        TASK_TYPE,
        BackgroundTaskStatus.RUNNING,
        DOCUMENT_ID,
        "worker-a",
        "token-a",
    )

    private fun failedState(
        id: String = TASK_ID.value,
        tenantId: String = TENANT_ID.value,
        type: String = TASK_TYPE,
        businessId: String = DOCUMENT_ID.value,
    ): TaskState = TaskState(
        Identifier(id),
        Identifier(tenantId),
        type,
        BackgroundTaskStatus.FAILED,
        Identifier(businessId),
    )

    private fun lease(
        id: String = TASK_ID.value,
        tenantId: String = TENANT_ID.value,
        type: String = TASK_TYPE,
        businessId: String = DOCUMENT_ID.value,
        owner: String = "worker-a",
        token: String? = "token-a",
    ): BackgroundTaskLease = BackgroundTaskLease(
        BackgroundTask(
            id = Identifier(id),
            tenantId = Identifier(tenantId),
            type = type,
            idempotencyKey = "task:$id",
            businessId = Identifier(businessId),
            status = BackgroundTaskStatus.RUNNING,
        ),
        owner,
        token,
    )

    private companion object {
        val TASK_ID = Identifier("task-1")
        val TENANT_ID = Identifier("tenant-1")
        val DOCUMENT_ID = Identifier("document-1")
        const val TASK_TYPE = "document.doctor.requested"
    }
}
