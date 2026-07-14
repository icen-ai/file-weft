package ai.icen.fw.application.metadata

import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.metadata.api.MetadataField
import ai.icen.fw.metadata.api.MetadataFieldType
import ai.icen.fw.metadata.api.MetadataProcessor
import ai.icen.fw.metadata.api.MetadataSchema
import ai.icen.fw.metadata.api.MetadataSchemaContext
import ai.icen.fw.metadata.api.MetadataSchemaResolver
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentMetadataServiceTest {
    @Test
    fun `trusted write processing reuses the authorized tenant snapshot without consulting tenant context again`() {
        val contexts = mutableListOf<MetadataSchemaContext>()
        val service = DocumentMetadataService(
            object : TenantProvider {
                override fun currentTenant(): TenantContext = error("trusted processing must not re-read tenant context")
            },
            object : MetadataSchemaResolver {
                override fun resolve(context: MetadataSchemaContext): MetadataSchema {
                    contexts += context
                    return MetadataSchema(
                        "invoice",
                        "2",
                        listOf(MetadataField("amount", MetadataFieldType.NUMBER, required = true)),
                    )
                }
            },
            object : MetadataProcessor {
                override fun process(
                    context: MetadataSchemaContext,
                    metadata: Map<String, String>,
                ): Map<String, String> {
                    contexts += context
                    return metadata
                }
            },
        )

        val result = service.processTrusted(
            Identifier("tenant-authorized"),
            "invoice",
            mapOf("amount" to "12.5"),
            DocumentMetadataService.CREATE_OPERATION,
        )

        assertEquals("tenant-authorized", contexts.single { it.schemaVersion == null }.tenantId)
        assertEquals("tenant-authorized", contexts.single { it.schemaVersion == "2" }.tenantId)
        assertEquals("invoice", result[DocumentMetadataService.SCHEMA_ID_KEY])
    }

    @Test
    fun `locks the current schema to its exact version and persists immutable markers`() {
        val contexts = mutableListOf<MetadataSchemaContext>()
        val schema = MetadataSchema(
            "invoice",
            "2",
            listOf(MetadataField("amount", MetadataFieldType.NUMBER, required = true)),
        )
        val resolver = object : MetadataSchemaResolver {
            override fun resolve(context: MetadataSchemaContext): MetadataSchema? {
                contexts += context
                return schema
            }
        }
        val processor = object : MetadataProcessor {
            override fun process(
                context: MetadataSchemaContext,
                metadata: Map<String, String>,
            ): Map<String, String> {
                contexts += context
                return mapOf("amount" to "12.50")
            }
        }
        val service = DocumentMetadataService(tenantProvider(), resolver, processor)

        val result = service.process("invoice", mapOf("amount" to "12.500"), "create")

        assertEquals(
            mapOf(
                "amount" to "12.50",
                DocumentMetadataService.SCHEMA_ID_KEY to "invoice",
                DocumentMetadataService.SCHEMA_VERSION_KEY to "2",
            ),
            result,
        )
        assertEquals(listOf(null, "2"), contexts.map { context -> context.schemaVersion })
        assertEquals(listOf("tenant-a", "tenant-a"), contexts.map { context -> context.tenantId })
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (result as MutableMap<String, String>)["amount"] = "private"
        }
    }

    @Test
    fun `fails closed before processing when schema is unavailable or markers are forged`() {
        var processorCalled = false
        val service = DocumentMetadataService(
            tenantProvider(),
            object : MetadataSchemaResolver {
                override fun resolve(context: MetadataSchemaContext): MetadataSchema? = null
            },
            object : MetadataProcessor {
                override fun process(
                    context: MetadataSchemaContext,
                    metadata: Map<String, String>,
                ): Map<String, String> {
                    processorCalled = true
                    return metadata
                }
            },
        )

        assertFailsWith<MetadataSchemaUnavailableException> {
            service.process("missing", emptyMap(), "create")
        }
        assertFailsWith<IllegalArgumentException> {
            service.process(
                "missing",
                mapOf(DocumentMetadataService.SCHEMA_ID_KEY to "forged"),
                "create",
            )
        }
        assertEquals(false, processorCalled)
    }

    private fun tenantProvider(): TenantProvider = object : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-a"))
    }
}
