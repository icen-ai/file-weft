package ai.icen.fw.metadata.runtime

import ai.icen.fw.metadata.api.MetadataProcessor
import ai.icen.fw.metadata.api.MetadataSchema
import ai.icen.fw.metadata.api.MetadataSchemaContext
import ai.icen.fw.metadata.api.MetadataSchemaResolver

/** Pure JVM metadata processing: resolve, validate, then canonicalize. */
class DefaultMetadataProcessor @JvmOverloads constructor(
    private val resolver: MetadataSchemaResolver,
    private val validator: MetadataValidator = MetadataValidator(),
    private val normalizer: MetadataNormalizer = MetadataNormalizer(validator),
) : MetadataProcessor {
    override fun process(
        context: MetadataSchemaContext,
        metadata: Map<String, String>,
    ): Map<String, String> {
        val snapshot = MetadataInputSnapshots.capture(metadata)
        val schema = resolveSchema(context)
        val evaluation = validator.evaluate(schema, snapshot)
        if (!evaluation.validationResult.valid) {
            throw MetadataValidationException(evaluation.validationResult)
        }
        return normalizer.normalize(evaluation)
    }

    private fun resolveSchema(context: MetadataSchemaContext): MetadataSchema {
        val schema = try {
            resolver.resolve(context)
        } catch (_: RuntimeException) {
            throw MetadataSchemaConfigurationException()
        } ?: throw MetadataSchemaNotFoundException()

        if (schema.id != context.schemaId) {
            throw MetadataSchemaConfigurationException()
        }
        if (context.schemaVersion != null && schema.version != context.schemaVersion) {
            throw MetadataSchemaConfigurationException()
        }
        return schema
    }
}
