package ai.icen.fw.metadata.runtime

import ai.icen.fw.metadata.api.MetadataField
import ai.icen.fw.metadata.api.MetadataFieldType
import ai.icen.fw.metadata.api.MetadataSchema
import ai.icen.fw.metadata.api.MetadataSchemaContext
import ai.icen.fw.metadata.api.MetadataSchemaResolver
import com.google.re2j.PatternSyntaxException as LinearPatternSyntaxException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the failure contract of [MetadataSchemaConfigurationException]: the
 * fixed message never changes because it can cross API boundaries, while the
 * original failure is retained as the cause for operators.
 */
class MetadataConfigurationFailureTest {
    @Test
    fun `keeps the no-argument constructor contract unchanged`() {
        val failure = MetadataSchemaConfigurationException()

        assertEquals(MetadataSchemaConfigurationException.MESSAGE, failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun `registry retains the regex syntax failure as the cause`() {
        val invalidPattern = MetadataSchema(
            "pattern",
            "1",
            listOf(MetadataField("title", MetadataFieldType.STRING, format = "[")),
        )

        val failure = assertFailsWith<MetadataSchemaConfigurationException> {
            MetadataSchemaRegistry(listOf(invalidPattern))
        }

        assertEquals(MetadataSchemaConfigurationException.MESSAGE, failure.message)
        assertTrue(failure.cause is LinearPatternSyntaxException)
    }

    @Test
    fun `registry keeps pure configuration checks free of a cause`() {
        val reserved = MetadataSchema(
            "document",
            "1",
            listOf(MetadataField("metadata.title", MetadataFieldType.STRING)),
        )

        val failure = assertFailsWith<MetadataSchemaConfigurationException> {
            MetadataSchemaRegistry(listOf(reserved))
        }

        assertEquals(MetadataSchemaConfigurationException.MESSAGE, failure.message)
        assertNull(failure.cause, "A reserved-name rejection has no underlying failure.")
    }

    @Test
    fun `registry retains unexpected schema list failures as the cause`() {
        val exploded = IllegalStateException("schema source exploded")
        val brokenSchemas = object : AbstractList<MetadataSchema>() {
            override val size: Int
                get() = 1

            override fun get(index: Int): MetadataSchema = throw exploded
        }

        val failure = assertFailsWith<MetadataSchemaConfigurationException> {
            MetadataSchemaRegistry(brokenSchemas)
        }

        assertEquals(MetadataSchemaConfigurationException.MESSAGE, failure.message)
        assertSame(exploded, failure.cause)
    }

    @Test
    fun `processor retains resolver failures as the cause`() {
        val exploded = IllegalStateException("resolver exploded")
        val processor = DefaultMetadataProcessor(
            object : MetadataSchemaResolver {
                override fun resolve(context: MetadataSchemaContext): MetadataSchema? = throw exploded
            },
        )

        val failure = assertFailsWith<MetadataSchemaConfigurationException> {
            processor.process(context(), mapOf("title" to "Report"))
        }

        assertEquals(MetadataSchemaConfigurationException.MESSAGE, failure.message)
        assertSame(exploded, failure.cause)
    }

    @Test
    fun `normalization retains the regex syntax failure as the cause`() {
        val failure = assertFailsWith<MetadataSchemaConfigurationException> {
            MetadataFieldCodec.normalize(
                MetadataField("code", MetadataFieldType.STRING, format = "["),
                "value",
            )
        }

        assertEquals(MetadataSchemaConfigurationException.MESSAGE, failure.message)
        assertTrue(failure.cause is LinearPatternSyntaxException)
    }

    private fun context(version: String? = null) = MetadataSchemaContext(
        "tenant-1",
        "document",
        "DOCUMENT",
        "UPLOAD",
        version,
    )
}
