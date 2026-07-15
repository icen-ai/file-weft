package ai.icen.fw.testkit.metadata

import ai.icen.fw.metadata.api.MetadataField
import ai.icen.fw.metadata.api.MetadataSchema
import ai.icen.fw.metadata.api.MetadataSchemaContext
import ai.icen.fw.metadata.api.MetadataSchemaResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Reusable tenant- and version-aware metadata schema resolution contract. */
abstract class MetadataSchemaResolverContractTest {
    protected abstract val metadataSchemaResolver: MetadataSchemaResolver

    /** A trusted context for which the provider must return a schema. */
    protected abstract fun knownContext(): MetadataSchemaContext

    /** A different tenant/schema/version context which must not resolve. */
    protected abstract fun absentContext(): MetadataSchemaContext

    @Test
    fun `resolves the requested schema identity and exact version`() {
        val context = knownContext()
        val schema = metadataSchemaResolver.resolve(context)

        assertNotNull(schema, "The configured known context must resolve a schema.")
        requireNotNull(schema)
        assertEquals(context.schemaId, schema.id, "A resolver must not substitute another schema id.")
        context.schemaVersion?.let { version ->
            assertEquals(version, schema.version, "An exact schema-version request must not fall back.")
        }
    }

    @Test
    fun `returns one stable schema snapshot for replayed resolution`() {
        val first = requireNotNull(metadataSchemaResolver.resolve(knownContext()))
        val replay = requireNotNull(metadataSchemaResolver.resolve(knownContext()))

        assertEquals(schemaSignature(first), schemaSignature(replay))
    }

    @Test
    fun `fails closed for an absent tenant schema or version`() {
        assertNull(
            metadataSchemaResolver.resolve(absentContext()),
            "A resolver must not fall back across tenant, schema, or version boundaries.",
        )
    }

    private fun schemaSignature(schema: MetadataSchema): List<Any> = listOf(
        schema.id,
        schema.version,
        schema.fields.map(::fieldSignature),
    )

    private fun fieldSignature(field: MetadataField): List<Any?> = listOf(
        field.name,
        field.type,
        field.required,
        field.allowedValues,
        field.maxLength,
        field.format,
    )
}
