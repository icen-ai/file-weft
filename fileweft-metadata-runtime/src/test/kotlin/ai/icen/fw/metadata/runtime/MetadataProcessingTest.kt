package ai.icen.fw.metadata.runtime

import ai.icen.fw.metadata.api.MetadataField
import ai.icen.fw.metadata.api.MetadataFieldType
import ai.icen.fw.metadata.api.MetadataSchema
import ai.icen.fw.metadata.api.MetadataSchemaContext
import ai.icen.fw.metadata.api.MetadataSchemaResolver
import ai.icen.fw.metadata.api.MetadataValidationIssueCode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetadataProcessingTest {
    private val schema = MetadataSchema(
        "document",
        "2",
        listOf(
            MetadataField("title", MetadataFieldType.STRING, required = true, maxLength = 20, format = "[A-Za-z ]+"),
            MetadataField("amount", MetadataFieldType.NUMBER),
            MetadataField("enabled", MetadataFieldType.BOOLEAN),
            MetadataField("publishedOn", MetadataFieldType.DATE),
            MetadataField(
                "visibility",
                MetadataFieldType.ENUM,
                allowedValues = listOf("PUBLIC", "PRIVATE"),
            ),
            MetadataField("tags", MetadataFieldType.STRING_LIST, maxLength = 8, format = "[a-z]+"),
        ),
    )

    @Test
    fun `normalizes every portable type in schema order`() {
        val metadata = linkedMapOf(
            "tags" to " [ \"alpha\" , \"beta\" ] ",
            "visibility" to "PUBLIC",
            "publishedOn" to " 2026-07-14 ",
            "enabled" to " TRUE ",
            "amount" to " +001.2300 ",
            "title" to "Annual Report",
        )

        val normalized = MetadataNormalizer().normalize(schema, metadata)

        assertEquals(
            listOf("title", "amount", "enabled", "publishedOn", "visibility", "tags"),
            normalized.keys.toList(),
        )
        assertEquals("Annual Report", normalized["title"])
        assertEquals("1.23", normalized["amount"])
        assertEquals("true", normalized["enabled"])
        assertEquals("2026-07-14", normalized["publishedOn"])
        assertEquals("PUBLIC", normalized["visibility"])
        assertEquals("[\"alpha\",\"beta\"]", normalized["tags"])
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (normalized as MutableMap<String, String>)["title"] = "changed"
        }
    }

    @Test
    fun `rejects missing unknown malformed and constraint-breaking values`() {
        val result = MetadataValidator().validate(
            schema,
            linkedMapOf(
                "amount" to "not-number",
                "enabled" to "yes",
                "publishedOn" to "2026-02-30",
                "visibility" to "INTERNAL",
                "tags" to "[\"TOO-LONG-UPPER\"]",
                "other" to "ignored",
            ),
        )

        assertFalse(result.valid)
        assertEquals(
            setOf(
                MetadataValidationIssueCode.MISSING_REQUIRED_FIELD,
                MetadataValidationIssueCode.INVALID_TYPE,
                MetadataValidationIssueCode.INVALID_ENUM_VALUE,
                MetadataValidationIssueCode.VALUE_TOO_LONG,
                MetadataValidationIssueCode.UNKNOWN_FIELD,
            ),
            result.issues.map { it.code }.toSet(),
        )

        val formatResult = MetadataValidator().validate(
            schema,
            mapOf("title" to "Report 2026"),
        )
        assertEquals(
            MetadataValidationIssueCode.FORMAT_MISMATCH,
            formatResult.issues.single().code,
        )
    }

    @Test
    fun `string list parser accepts only bounded JSON string arrays`() {
        val validator = MetadataValidator()
        val listOnly = MetadataSchema(
            "list",
            "1",
            listOf(MetadataField("items", MetadataFieldType.STRING_LIST)),
        )

        listOf("[1]", "[\"a\",]", "{\"a\":1}", "[\"\\x\"]", "[\"\uD800\"]").forEach { raw ->
            val result = validator.validate(listOnly, mapOf("items" to raw))
            assertEquals(MetadataValidationIssueCode.INVALID_TYPE, result.issues.single().code)
        }

        val tooManyItems = List(65) { "\"x\"" }.joinToString(prefix = "[", postfix = "]")
        assertEquals(
            MetadataValidationIssueCode.VALUE_TOO_LONG,
            validator.validate(listOnly, mapOf("items" to tooManyItems)).issues.single().code,
        )
    }

    @Test
    fun `bounds aggregate input before schema validation`() {
        val oversizedFieldSet = (0..128).associate { "field$it" to "x" }

        val result = MetadataValidator().validate(schema, oversizedFieldSet)

        assertEquals(MetadataValidationIssueCode.TOO_MANY_FIELDS, result.issues.single().code)
    }

    @Test
    fun `processor supports exact schemas and keeps raw values out of failures`() {
        val historical = MetadataSchema(
            "document",
            "1",
            listOf(MetadataField("legacyTitle", MetadataFieldType.STRING, required = true)),
        )
        val processor = DefaultMetadataProcessor(
            MetadataSchemaRegistry(listOf(schema), listOf(historical)),
        )

        assertEquals(
            mapOf("legacyTitle" to "旧标题"),
            processor.process(context("1"), mapOf("legacyTitle" to "旧标题")),
        )

        val secret = "raw-secret-value"
        val failure = assertFailsWith<MetadataValidationException> {
            processor.process(context(), mapOf("title" to "Report", "amount" to secret))
        }
        assertTrue(failure.javaClass.superclass === IllegalArgumentException::class.java)
        assertEquals(MetadataValidationException.MESSAGE, failure.message)
        assertTrue(failure.validationResult.issues.none { issue ->
            issue.message.contains(secret) || issue.fieldName?.contains(secret) == true
        })
    }

    @Test
    fun `processor classifies missing and mismatched resolver results with fixed messages`() {
        val missing = DefaultMetadataProcessor(
            object : MetadataSchemaResolver {
                override fun resolve(context: MetadataSchemaContext): MetadataSchema? = null
            },
        )
        val missingFailure = assertFailsWith<MetadataSchemaNotFoundException> {
            missing.process(context(), mapOf("title" to "Report"))
        }
        assertTrue(missingFailure.javaClass.superclass === NoSuchElementException::class.java)
        assertEquals(MetadataSchemaNotFoundException.MESSAGE, missingFailure.message)

        val otherSchema = MetadataSchema("other", "2", emptyList())
        val mismatched = DefaultMetadataProcessor(
            object : MetadataSchemaResolver {
                override fun resolve(context: MetadataSchemaContext): MetadataSchema = otherSchema
            },
        )
        val mismatchFailure = assertFailsWith<MetadataSchemaConfigurationException> {
            mismatched.process(context(), mapOf("title" to "Report"))
        }
        assertTrue(mismatchFailure.javaClass.superclass === IllegalStateException::class.java)
        assertEquals(MetadataSchemaConfigurationException.MESSAGE, mismatchFailure.message)
    }

    @Test
    fun `format validation uses a linear-time regular expression dialect`() {
        val unsupportedBackReference = MetadataSchema(
            "unsafe-dialect",
            "1",
            listOf(MetadataField("value", MetadataFieldType.STRING, format = "(a)\\1")),
        )
        assertFailsWith<MetadataSchemaConfigurationException> {
            MetadataSchemaRegistry(listOf(unsupportedBackReference))
        }

        val adversarialSchema = MetadataSchema(
            "adversarial",
            "1",
            listOf(MetadataField("value", MetadataFieldType.STRING, format = "(a+)+$")),
        )
        val result = MetadataValidator().validate(
            adversarialSchema,
            mapOf("value" to ("a".repeat(16_383) + "!")),
        )

        assertEquals(MetadataValidationIssueCode.FORMAT_MISMATCH, result.issues.single().code)
    }

    private fun context(version: String? = null) = MetadataSchemaContext(
        "tenant-1",
        "document",
        "DOCUMENT",
        "UPLOAD",
        version,
    )
}
