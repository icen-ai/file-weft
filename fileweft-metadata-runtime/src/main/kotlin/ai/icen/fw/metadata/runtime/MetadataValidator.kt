package ai.icen.fw.metadata.runtime

import ai.icen.fw.metadata.api.MetadataSchema
import ai.icen.fw.metadata.api.MetadataValidationIssue
import ai.icen.fw.metadata.api.MetadataValidationIssueCode
import ai.icen.fw.metadata.api.MetadataValidationResult
import java.util.LinkedHashMap

internal class MetadataEvaluation(
    val validationResult: MetadataValidationResult,
    val normalizedValues: Map<String, String>,
)

class MetadataValidator {
    fun validate(
        schema: MetadataSchema,
        metadata: Map<String, String>,
    ): MetadataValidationResult = evaluate(schema, MetadataInputSnapshots.capture(metadata)).validationResult

    internal fun evaluate(
        schema: MetadataSchema,
        snapshot: MetadataInputSnapshot,
    ): MetadataEvaluation {
        MetadataSchemaConfiguration.validate(schema)
        if (!snapshot.usable) {
            return MetadataEvaluation(MetadataValidationResult(snapshot.issues), emptyMap())
        }

        val issues = ArrayList(snapshot.issues)
        val normalizedValues = LinkedHashMap<String, String>()

        schema.fields.forEach { field ->
            if (!snapshot.values.containsKey(field.name)) {
                if (field.required) {
                    addIssue(
                        issues,
                        MetadataValidationIssue(
                            MetadataValidationIssueCode.MISSING_REQUIRED_FIELD,
                            field.name,
                        ),
                    )
                }
                return@forEach
            }

            val rawValue = snapshot.values.getValue(field.name)
            when (val normalization = MetadataFieldCodec.normalize(field, rawValue)) {
                is FieldNormalization.Success ->
                    normalizedValues[field.name] = normalization.encodedValue
                is FieldNormalization.Failure ->
                    addIssue(
                        issues,
                        MetadataValidationIssue(normalization.issueCode, field.name),
                    )
            }
        }

        snapshot.values.keys
            .asSequence()
            .filter { schema.findField(it) == null }
            .sorted()
            .forEach { fieldName ->
                addIssue(
                    issues,
                    MetadataValidationIssue(MetadataValidationIssueCode.UNKNOWN_FIELD, fieldName),
                )
            }

        return MetadataEvaluation(
            MetadataValidationResult(issues),
            normalizedValues,
        )
    }

    private fun addIssue(
        issues: MutableList<MetadataValidationIssue>,
        issue: MetadataValidationIssue,
    ) {
        if (issues.size < MetadataRuntimeLimits.MAX_ISSUES) {
            issues += issue
        }
    }
}
