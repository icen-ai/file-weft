package ai.icen.fw.metadata.runtime

import ai.icen.fw.metadata.api.MetadataField
import ai.icen.fw.metadata.api.MetadataFieldType
import ai.icen.fw.metadata.api.MetadataSchema
import ai.icen.fw.metadata.api.MetadataValidationIssueCode
import com.google.re2j.PatternSyntaxException as LinearPatternSyntaxException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs

internal sealed class FieldNormalization {
    class Success(
        val encodedValue: String,
        val logicalValues: List<String>,
    ) : FieldNormalization()

    class Failure(
        val issueCode: MetadataValidationIssueCode,
    ) : FieldNormalization()
}

internal object MetadataSchemaConfiguration {
    /**
     * Re-checks the immutable schema on every evaluation. This stays
     * per-request on purpose: with format compilation behind
     * [MetadataFormatPatterns], the remaining work is a cheap O(fields)
     * reserved-prefix scan, and caching the verdict per schema identity would
     * add invalidation complexity for no measurable win. The check is a pure
     * function of the immutable schema, so repeating it is always safe.
     */
    fun validate(schema: MetadataSchema) {
        try {
            schema.fields.forEach { field ->
                if (isReservedFieldName(field.name)) {
                    throw MetadataSchemaConfigurationException()
                }
                field.format?.let { MetadataFormatPatterns.compile(it) }
            }
        } catch (exception: MetadataSchemaConfigurationException) {
            throw exception
        } catch (exception: LinearPatternSyntaxException) {
            throw MetadataSchemaConfigurationException(exception)
        } catch (exception: RuntimeException) {
            throw MetadataSchemaConfigurationException(exception)
        }
    }

    private fun isReservedFieldName(name: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT)
        return normalized.startsWith("metadata.") ||
            normalized.startsWith("catalog.") ||
            normalized.startsWith("fileweft.")
    }
}

internal object MetadataFieldCodec {
    private val numberPattern = Pattern.compile(
        "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?",
    )
    private val datePattern = Pattern.compile("\\d{4}-\\d{2}-\\d{2}")

    fun normalize(field: MetadataField, rawValue: String): FieldNormalization {
        val initial = when (field.type) {
            MetadataFieldType.STRING -> success(rawValue)
            MetadataFieldType.ENUM -> normalizeEnum(field, rawValue)
            MetadataFieldType.NUMBER -> normalizeNumber(rawValue)
            MetadataFieldType.BOOLEAN -> normalizeBoolean(rawValue)
            MetadataFieldType.DATE -> normalizeDate(rawValue)
            MetadataFieldType.STRING_LIST -> normalizeStringList(rawValue)
        }
        if (initial !is FieldNormalization.Success) {
            return initial
        }

        val maximumLength = field.maxLength
        if (maximumLength != null && initial.logicalValues.any { it.length > maximumLength }) {
            return failure(MetadataValidationIssueCode.VALUE_TOO_LONG)
        }

        val format = field.format
        if (format != null) {
            val pattern = try {
                MetadataFormatPatterns.compile(format)
            } catch (exception: LinearPatternSyntaxException) {
                throw MetadataSchemaConfigurationException(exception)
            }
            if (initial.logicalValues.any { !pattern.matcher(it).matches() }) {
                return failure(MetadataValidationIssueCode.FORMAT_MISMATCH)
            }
        }
        return initial
    }

    private fun normalizeEnum(field: MetadataField, rawValue: String): FieldNormalization =
        if (field.allowedValues.contains(rawValue)) {
            success(rawValue)
        } else {
            failure(MetadataValidationIssueCode.INVALID_ENUM_VALUE)
        }

    private fun normalizeNumber(rawValue: String): FieldNormalization {
        val candidate = rawValue.trim()
        if (candidate.isEmpty() || !numberPattern.matcher(candidate).matches()) {
            return failure(MetadataValidationIssueCode.INVALID_TYPE)
        }
        if (candidate.length > MetadataRuntimeLimits.MAX_NUMBER_SOURCE_UTF16_LENGTH) {
            return failure(MetadataValidationIssueCode.VALUE_TOO_LONG)
        }

        val number = try {
            BigDecimal(candidate)
        } catch (_: NumberFormatException) {
            return failure(MetadataValidationIssueCode.INVALID_TYPE)
        }
        if (
            number.precision() > MetadataRuntimeLimits.MAX_NUMBER_PRECISION ||
            abs(number.scale().toLong()) > MetadataRuntimeLimits.MAX_NUMBER_SCALE_MAGNITUDE
        ) {
            return failure(MetadataValidationIssueCode.VALUE_TOO_LONG)
        }

        val canonical = if (number.compareTo(BigDecimal.ZERO) == 0) {
            "0"
        } else {
            number.stripTrailingZeros().toPlainString()
        }
        if (canonical.length > MetadataRuntimeLimits.MAX_VALUE_UTF16_LENGTH) {
            return failure(MetadataValidationIssueCode.VALUE_TOO_LONG)
        }
        return success(canonical)
    }

    private fun normalizeBoolean(rawValue: String): FieldNormalization {
        val candidate = rawValue.trim()
        return when {
            candidate.equals("true", ignoreCase = true) -> success("true")
            candidate.equals("false", ignoreCase = true) -> success("false")
            else -> failure(MetadataValidationIssueCode.INVALID_TYPE)
        }
    }

    private fun normalizeDate(rawValue: String): FieldNormalization {
        val candidate = rawValue.trim()
        if (!datePattern.matcher(candidate).matches()) {
            return failure(MetadataValidationIssueCode.INVALID_TYPE)
        }
        val date = try {
            LocalDate.parse(candidate, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: DateTimeParseException) {
            return failure(MetadataValidationIssueCode.INVALID_TYPE)
        }
        return success(date.toString())
    }

    private fun normalizeStringList(rawValue: String): FieldNormalization {
        val values = try {
            StrictJsonStringListCodec.decode(rawValue)
        } catch (exception: StringListDecodingException) {
            return failure(exception.issueCode)
        }
        return FieldNormalization.Success(
            StrictJsonStringListCodec.encode(values),
            values,
        )
    }

    private fun success(value: String): FieldNormalization.Success =
        FieldNormalization.Success(value, listOf(value))

    private fun failure(code: MetadataValidationIssueCode): FieldNormalization.Failure =
        FieldNormalization.Failure(code)
}
