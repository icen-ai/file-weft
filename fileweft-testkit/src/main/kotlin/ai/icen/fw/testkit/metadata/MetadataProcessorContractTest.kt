package ai.icen.fw.testkit.metadata

import ai.icen.fw.metadata.api.MetadataProcessor
import ai.icen.fw.metadata.api.MetadataSchemaContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Collections

/** Reusable deterministic canonicalization contract for metadata processors. */
abstract class MetadataProcessorContractTest {
    protected abstract val metadataProcessor: MetadataProcessor

    protected abstract fun processingContext(): MetadataSchemaContext

    protected abstract fun validInput(): Map<String, String>

    protected abstract fun expectedCanonicalOutput(): Map<String, String>

    @Test
    fun `canonicalizes deterministically without mutating caller input`() {
        val mutableInput = LinkedHashMap(validInput())
        val inputSnapshot = LinkedHashMap(mutableInput)
        val readOnlyInput = Collections.unmodifiableMap(mutableInput)

        val first = metadataProcessor.process(processingContext(), readOnlyInput)
        val replay = metadataProcessor.process(processingContext(), readOnlyInput)

        assertEquals(inputSnapshot, mutableInput, "A processor must treat caller-owned input as read-only.")
        assertEquals(expectedCanonicalOutput(), first)
        assertEquals(first, replay, "Canonical metadata must be deterministic for the same trusted context.")
    }

    @Test
    fun `returns an immutable canonical snapshot`() {
        val result = metadataProcessor.process(processingContext(), validInput())

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (result as MutableMap<String, String>)["testkit-mutation"] = "forbidden"
        }
    }
}
