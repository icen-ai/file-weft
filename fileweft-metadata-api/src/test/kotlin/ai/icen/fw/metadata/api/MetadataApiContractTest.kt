package ai.icen.fw.metadata.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MetadataApiContractTest {
    @Test
    fun `exposes all portable field types and immutable schema state`() {
        assertEquals(
            listOf("STRING", "NUMBER", "BOOLEAN", "DATE", "ENUM", "STRING_LIST"),
            MetadataFieldType.entries.map { it.name },
        )

        val allowedValues = mutableListOf("PUBLIC", "PRIVATE")
        val field = MetadataField(
            "visibility",
            MetadataFieldType.ENUM,
            required = true,
            allowedValues = allowedValues,
            maxLength = 16,
        )
        val fields = mutableListOf(field)
        val schema = MetadataSchema("document", "2", fields)

        allowedValues += "INTERNAL"
        fields.clear()

        assertEquals(listOf("PUBLIC", "PRIVATE"), field.allowedValues)
        assertEquals(listOf(field), schema.fields)
        assertEquals(field, schema.findField("visibility"))
        assertEquals(null, schema.findField("missing"))
    }

    @Test
    fun `rejects ambiguous or unbounded schema definitions`() {
        val field = MetadataField("title", MetadataFieldType.STRING)

        assertFailsWith<IllegalArgumentException> {
            MetadataSchema("document", "1", listOf(field, field))
        }
        assertFailsWith<IllegalArgumentException> {
            MetadataField("state", MetadataFieldType.ENUM)
        }
        assertFailsWith<IllegalArgumentException> {
            MetadataField("title", MetadataFieldType.STRING, allowedValues = listOf("x"))
        }
        assertFailsWith<IllegalArgumentException> {
            MetadataField("title", MetadataFieldType.STRING, maxLength = 0)
        }
    }

    @Test
    fun `carries trusted exact-version context and value-free validation issues`() {
        val context = MetadataSchemaContext(
            "tenant-1",
            "document",
            "DOCUMENT",
            "UPLOAD",
            "7",
        )
        val issue = MetadataValidationIssue(
            MetadataValidationIssueCode.INVALID_TYPE,
            "amount",
        )
        val source = mutableListOf(issue)
        val result = MetadataValidationResult(source)
        source.clear()

        assertEquals("7", context.schemaVersion)
        assertFalse(result.valid)
        assertFalse(result.isValid())
        assertEquals(listOf(issue), result.issues)
        assertEquals("Metadata value does not match the declared type.", issue.message)
        assertNotNull(issue.fieldName)
        assertTrue(MetadataValidationResult.success().valid)
    }
}
