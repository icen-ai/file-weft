package com.fileweft.web.runtime.v1.workflow

import com.fileweft.core.id.Identifier
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowPageCursorCodecTest {
    private val taskCodec = WorkflowPageCursorCodec(WorkflowPageCursorCodec.TASK_KIND)
    private val historyCodec = WorkflowPageCursorCodec(WorkflowPageCursorCodec.HISTORY_KIND)

    @Test
    fun `round trips both cursor kinds with Unicode and boundary timestamps`() {
        val unicodeId = Identifier("审批-🚀-流程")

        val taskEncoded = taskCodec.encode(0, unicodeId)
        val historyEncoded = historyCodec.encode(Long.MAX_VALUE, unicodeId)
        val task = taskCodec.decode(taskEncoded)
        val history = historyCodec.decode(historyEncoded)

        listOf(taskEncoded, historyEncoded).forEach { encoded ->
            assertTrue(encoded.matches(Regex("[A-Za-z0-9_-]+")))
            assertFalse(encoded.contains('='))
        }
        assertEquals(0, task.createdTime)
        assertEquals(unicodeId, task.id)
        assertEquals(Long.MAX_VALUE, history.createdTime)
        assertEquals(unicodeId, history.id)
        assertFalse(taskEncoded == historyEncoded)
    }

    @Test
    fun `rejects a valid cursor belonging to the other workflow page kind`() {
        val taskCursor = taskCodec.encode(10, Identifier("task-1"))
        val historyCursor = historyCodec.encode(20, Identifier("workflow-1"))

        listOf(
            assertFailsWith<IllegalArgumentException> { historyCodec.decode(taskCursor) },
            assertFailsWith<IllegalArgumentException> { taskCodec.decode(historyCursor) },
        ).forEach { failure ->
            assertEquals("Invalid workflow page cursor.", failure.message)
            assertFalse(failure.message.orEmpty().contains(taskCursor))
            assertFalse(failure.message.orEmpty().contains(historyCursor))
        }
    }

    @Test
    fun `rejects malformed version time lengths alphabet and unsafe Unicode without echoing input`() {
        val malformedSurrogate = byteArrayOf(0xd8.toByte(), 0x00)
        val invalidInputs = listOf(
            "***",
            "A".repeat(513),
            encoded(byteArrayOf(1, WorkflowPageCursorCodec.TASK_KIND)),
            payload(version = 2, kind = WorkflowPageCursorCodec.TASK_KIND, time = 1, id = "task-1"),
            payload(version = 1, kind = 9, time = 1, id = "task-1"),
            payload(version = 1, kind = WorkflowPageCursorCodec.TASK_KIND, time = -1, id = "task-1"),
            payload(version = 1, kind = WorkflowPageCursorCodec.TASK_KIND, time = 1, id = "", declaredLength = 0),
            payload(version = 1, kind = WorkflowPageCursorCodec.TASK_KIND, time = 1, id = "A", declaredLength = 1),
            payload(version = 1, kind = WorkflowPageCursorCodec.TASK_KIND, time = 1, id = "A", declaredLength = 258),
            payload(version = 1, kind = WorkflowPageCursorCodec.TASK_KIND, time = 1, id = "A", declaredLength = 4),
            payload(version = 1, kind = WorkflowPageCursorCodec.TASK_KIND, time = 1, id = "task\u0000-1"),
            payload(version = 1, kind = WorkflowPageCursorCodec.TASK_KIND, time = 1, id = "task\u200d-1"),
            payloadBytes(
                version = 1,
                kind = WorkflowPageCursorCodec.TASK_KIND,
                time = 1,
                idBytes = malformedSurrogate,
            ),
            payload(version = 1, kind = WorkflowPageCursorCodec.TASK_KIND, time = 1, id = "task-1") + "A",
        )

        invalidInputs.forEach { input ->
            val failure = assertFailsWith<IllegalArgumentException> { taskCodec.decode(input) }

            assertEquals("Invalid workflow page cursor.", failure.message)
            assertFalse(failure.message.orEmpty().contains(input))
        }
    }

    @Test
    fun `rejects invalid encoder output values with one fixed diagnostic`() {
        val invalidIds = listOf(
            Identifier("x".repeat(129)),
            Identifier("task\u0000-1"),
            Identifier("task\u200d-1"),
            Identifier("\ud800"),
        )

        val failures = buildList {
            add(assertFailsWith<IllegalStateException> { taskCodec.encode(-1, Identifier("task-1")) })
            invalidIds.forEach { id ->
                add(assertFailsWith<IllegalStateException> { taskCodec.encode(1, id) })
            }
        }

        failures.forEach { failure ->
            assertEquals("Workflow query returned an invalid page cursor.", failure.message)
        }
        assertFailsWith<IllegalArgumentException> { WorkflowPageCursorCodec(3) }
    }

    private fun payload(
        version: Int,
        kind: Byte,
        time: Long,
        id: String,
        declaredLength: Int = -1,
    ): String {
        val idBytes = id.toByteArray(StandardCharsets.UTF_16BE)
        return payloadBytes(version, kind, time, idBytes, declaredLength)
    }

    private fun payloadBytes(
        version: Int,
        kind: Byte,
        time: Long,
        idBytes: ByteArray,
        declaredLength: Int = -1,
    ): String {
        val length = if (declaredLength >= 0) declaredLength else idBytes.size
        return encoded(
            ByteBuffer.allocate(2 + Long.SIZE_BYTES + Short.SIZE_BYTES + idBytes.size)
                .put(version.toByte())
                .put(kind)
                .putLong(time)
                .putShort(length.toShort())
                .put(idBytes)
                .array(),
        )
    }

    private fun encoded(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
