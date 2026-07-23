package ai.icen.fw.application.metadata

import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.metadata.api.MetadataField
import ai.icen.fw.metadata.api.MetadataFieldType
import ai.icen.fw.metadata.api.MetadataProcessor
import ai.icen.fw.metadata.api.MetadataSchema
import ai.icen.fw.metadata.api.MetadataSchemaContext
import ai.icen.fw.metadata.api.MetadataSchemaResolver
import ai.icen.fw.spi.observability.FileWeftLogger
import ai.icen.fw.spi.observability.LogContext
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    @Test
    fun `logs schema resolution failures with the throwable and the tenant context`() {
        val logger = RecordingLogger()
        val failure = IllegalStateException("registry exploded")
        val service = DocumentMetadataService(
            tenantProvider(),
            object : MetadataSchemaResolver {
                override fun resolve(context: MetadataSchemaContext): MetadataSchema? = throw failure
            },
            passingProcessor(),
            logger,
        )

        assertFailsWith<MetadataConfigurationException> {
            service.process("invoice", emptyMap(), "create")
        }

        val error = logger.errors.single()
        assertEquals("Metadata schema resolution failed.", error.message)
        assertSame(failure, error.throwable)
        assertEquals(Identifier("tenant-a"), error.context.tenantId)
    }

    @Test
    fun `logs processor configuration failures and rethrows them unchanged`() {
        val logger = RecordingLogger()
        val failure = IllegalStateException("schema configuration is invalid")
        val service = DocumentMetadataService(
            tenantProvider(),
            resolverReturning(invoiceSchema()),
            object : MetadataProcessor {
                override fun process(
                    context: MetadataSchemaContext,
                    metadata: Map<String, String>,
                ): Map<String, String> = throw failure
            },
            logger,
        )

        val thrown = assertFailsWith<IllegalStateException> {
            service.process("invoice", emptyMap(), "create")
        }

        assertSame(failure, thrown, "Configuration failures from the processor must propagate unchanged.")
        val error = logger.errors.single()
        assertEquals("Metadata processing failed on schema configuration.", error.message)
        assertSame(failure, error.throwable)
        assertEquals(Identifier("tenant-a"), error.context.tenantId)
    }

    @Test
    fun `logs and wraps schema disappearance during processing`() {
        val logger = RecordingLogger()
        val service = DocumentMetadataService(
            tenantProvider(),
            resolverReturning(invoiceSchema()),
            object : MetadataProcessor {
                override fun process(
                    context: MetadataSchemaContext,
                    metadata: Map<String, String>,
                ): Map<String, String> = throw NoSuchElementException("schema is gone")
            },
            logger,
        )

        assertFailsWith<MetadataConfigurationException> {
            service.process("invoice", emptyMap(), "create")
        }
        assertEquals(1, logger.errors.size)
        assertEquals(Identifier("tenant-a"), logger.errors.single().context.tenantId)
    }

    @Test
    fun `logs the resolved-schema configuration guard without a throwable`() {
        val logger = RecordingLogger()
        val service = DocumentMetadataService(
            tenantProvider(),
            resolverReturning(
                MetadataSchema(
                    "invoice",
                    "2",
                    listOf(MetadataField("metadata.forged", MetadataFieldType.STRING)),
                ),
            ),
            passingProcessor(),
            logger,
        )

        assertFailsWith<MetadataConfigurationException> {
            service.process("invoice", emptyMap(), "create")
        }

        val error = logger.errors.single()
        assertNull(error.throwable, "A guard rejection has no underlying failure.")
        assertEquals(Identifier("tenant-a"), error.context.tenantId)
    }

    @Test
    fun `does not log input validation failures`() {
        val logger = RecordingLogger()
        val service = DocumentMetadataService(
            tenantProvider(),
            resolverReturning(invoiceSchema()),
            object : MetadataProcessor {
                override fun process(
                    context: MetadataSchemaContext,
                    metadata: Map<String, String>,
                ): Map<String, String> = throw IllegalArgumentException("Metadata validation failed.")
            },
            logger,
        )

        assertFailsWith<IllegalArgumentException> {
            service.process("invoice", emptyMap(), "create")
        }
        assertFailsWith<IllegalArgumentException> {
            service.process(
                "invoice",
                mapOf(DocumentMetadataService.SCHEMA_ID_KEY to "forged"),
                "create",
            )
        }
        assertTrue(logger.errors.isEmpty(), "Input validation failures are normal business flow.")
    }

    @Test
    fun `stays silent without a logger while failure behavior is unchanged`() {
        val service = DocumentMetadataService(
            tenantProvider(),
            object : MetadataSchemaResolver {
                override fun resolve(context: MetadataSchemaContext): MetadataSchema? =
                    throw IllegalStateException("registry exploded")
            },
            passingProcessor(),
        )

        assertFailsWith<MetadataConfigurationException> {
            service.process("invoice", emptyMap(), "create")
        }
    }

    private fun invoiceSchema(): MetadataSchema = MetadataSchema(
        "invoice",
        "2",
        listOf(MetadataField("amount", MetadataFieldType.NUMBER, required = true)),
    )

    private fun resolverReturning(schema: MetadataSchema): MetadataSchemaResolver = object : MetadataSchemaResolver {
        override fun resolve(context: MetadataSchemaContext): MetadataSchema = schema
    }

    private fun passingProcessor(): MetadataProcessor = object : MetadataProcessor {
        override fun process(
            context: MetadataSchemaContext,
            metadata: Map<String, String>,
        ): Map<String, String> = metadata
    }

    private class RecordingLogger : FileWeftLogger {
        data class ErrorEvent(val message: String, val throwable: Throwable?, val context: LogContext)

        val errors = mutableListOf<ErrorEvent>()

        override fun info(message: String, context: LogContext) = Unit

        override fun warn(message: String, context: LogContext) = Unit

        override fun error(message: String, throwable: Throwable?, context: LogContext) {
            errors += ErrorEvent(message, throwable, context)
        }

        override fun debug(message: String, context: LogContext) = Unit
    }

    private fun tenantProvider(): TenantProvider = object : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-a"))
    }
}
