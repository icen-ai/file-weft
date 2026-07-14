package ai.icen.fw.web.runtime.v1.document

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DocumentMetadataApiInputsTest {
    @Test
    fun `decodes bounded repeated field value parts without truncating values`() {
        val command = checkNotNull(DocumentMetadataApiInputs.parse(
            listOf("invoice"),
            listOf("amount=12.50", "expression=a=b"),
        ))

        assertEquals("invoice", command.schemaId)
        assertEquals(mapOf("amount" to "12.50", "expression" to "a=b"), command.values)
        assertNull(DocumentMetadataApiInputs.parse(null, null))
    }

    @Test
    fun `rejects metadata without one schema malformed entries and duplicates`() {
        assertFailsWith<IllegalArgumentException> {
            DocumentMetadataApiInputs.parse(null, listOf("amount=12.50"))
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentMetadataApiInputs.parse(listOf("one", "two"), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentMetadataApiInputs.parse(listOf("invoice"), listOf("malformed"))
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentMetadataApiInputs.parse(listOf("invoice"), listOf("amount=1", "amount=2"))
        }
    }

    @Test
    fun `matches the schema runtime field bound`() {
        val maximum = (0 until 128).map { index -> "field$index=value" }

        assertEquals(128, checkNotNull(DocumentMetadataApiInputs.parse(listOf("invoice"), maximum)).values.size)
        assertFailsWith<IllegalArgumentException> {
            DocumentMetadataApiInputs.parse(listOf("invoice"), maximum + "overflow=value")
        }
    }
}
