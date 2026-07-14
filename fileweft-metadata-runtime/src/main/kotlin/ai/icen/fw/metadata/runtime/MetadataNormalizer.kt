package ai.icen.fw.metadata.runtime

import ai.icen.fw.metadata.api.MetadataSchema
import java.util.Collections
import java.util.LinkedHashMap

class MetadataNormalizer @JvmOverloads constructor(
    private val validator: MetadataValidator = MetadataValidator(),
) {
    fun normalize(
        schema: MetadataSchema,
        metadata: Map<String, String>,
    ): Map<String, String> {
        val evaluation = validator.evaluate(schema, MetadataInputSnapshots.capture(metadata))
        return normalize(evaluation)
    }

    internal fun normalize(evaluation: MetadataEvaluation): Map<String, String> {
        if (!evaluation.validationResult.valid) {
            throw MetadataValidationException(evaluation.validationResult)
        }
        return Collections.unmodifiableMap(LinkedHashMap(evaluation.normalizedValues))
    }
}
