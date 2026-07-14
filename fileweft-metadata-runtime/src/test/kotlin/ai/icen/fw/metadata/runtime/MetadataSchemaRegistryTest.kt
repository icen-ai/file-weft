package ai.icen.fw.metadata.runtime

import ai.icen.fw.metadata.api.MetadataField
import ai.icen.fw.metadata.api.MetadataFieldType
import ai.icen.fw.metadata.api.MetadataSchema
import ai.icen.fw.metadata.api.MetadataSchemaContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MetadataSchemaRegistryTest {
    @Test
    fun `resolves explicit current and exact historical versions`() {
        val current = schema("2")
        val historical = schema("1")
        val registry = MetadataSchemaRegistry(listOf(current), listOf(historical))

        assertEquals(current, registry.findCurrent("document"))
        assertEquals(historical, registry.findExact("document", "1"))
        assertEquals(current, registry.resolve(context()))
        assertEquals(historical, registry.resolve(context("1")))
        assertNull(registry.resolve(context("missing")))
    }

    @Test
    fun `rejects duplicate current orphan history and invalid trusted regex`() {
        assertFailsWith<MetadataSchemaConfigurationException> {
            MetadataSchemaRegistry(listOf(schema("1"), schema("2")))
        }
        assertFailsWith<MetadataSchemaConfigurationException> {
            MetadataSchemaRegistry(
                listOf(schema("1")),
                listOf(MetadataSchema("other", "0", emptyList())),
            )
        }
        val invalidPattern = MetadataSchema(
            "pattern",
            "1",
            listOf(MetadataField("title", MetadataFieldType.STRING, format = "[")),
        )
        val failure = assertFailsWith<MetadataSchemaConfigurationException> {
            MetadataSchemaRegistry(listOf(invalidPattern))
        }
        assertEquals(MetadataSchemaConfigurationException.MESSAGE, failure.message)
    }

    @Test
    fun `rejects fields in every reserved persistence namespace with a fixed exception`() {
        listOf(
            "metadata.title",
            "catalog.folder-id",
            "fileweft.internal",
            "MeTaDaTa.case-insensitive",
        ).forEach { fieldName ->
            val failure = assertFailsWith<MetadataSchemaConfigurationException>(fieldName) {
                MetadataSchemaRegistry(
                    listOf(
                        MetadataSchema(
                            "document",
                            "1",
                            listOf(MetadataField(fieldName, MetadataFieldType.STRING)),
                        ),
                    ),
                )
            }

            assertEquals(MetadataSchemaConfigurationException.MESSAGE, failure.message, fieldName)
        }
    }

    private fun schema(version: String) = MetadataSchema(
        "document",
        version,
        listOf(MetadataField("title", MetadataFieldType.STRING)),
    )

    private fun context(version: String? = null) = MetadataSchemaContext(
        "tenant-1",
        "document",
        "DOCUMENT",
        "UPLOAD",
        version,
    )
}
