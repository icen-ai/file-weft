package com.fileweft.starter.boot3

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileWeftWorkerSchedulerTest {
    @Test
    fun `continues task polling after an outbox cycle fails`() {
        val properties = FileWeftProperties.WorkerProperties().apply { enabled = true }
        var outboxCalls = 0
        var taskCalls = 0
        val scheduler = FileWeftWorkerScheduler(
            properties,
            { outboxCalls++; throw IllegalStateException("temporary database outage") },
            { taskCalls++ },
        )

        scheduler.processAvailable()

        assertEquals(1, outboxCalls)
        assertEquals(1, taskCalls)
    }

    @Test
    fun `does not construct an implicit worker role`() {
        assertFailsWith<IllegalArgumentException> {
            FileWeftWorkerScheduler(FileWeftProperties.WorkerProperties(), {}, null)
        }
    }
}
