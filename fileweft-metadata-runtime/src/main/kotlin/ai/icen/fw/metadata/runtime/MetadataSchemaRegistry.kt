package ai.icen.fw.metadata.runtime

import ai.icen.fw.metadata.api.MetadataSchema
import ai.icen.fw.metadata.api.MetadataSchemaContext
import ai.icen.fw.metadata.api.MetadataSchemaResolver
import java.util.LinkedHashMap

private data class MetadataSchemaKey(
    val id: String,
    val version: String,
)

/**
 * Immutable in-memory registry with one explicitly declared current schema per
 * schema id and optional exact-version history.
 */
class MetadataSchemaRegistry @JvmOverloads constructor(
    currentSchemas: List<MetadataSchema>,
    historicalSchemas: List<MetadataSchema> = emptyList(),
) : MetadataSchemaResolver {
    private val currentById: Map<String, MetadataSchema>
    private val schemasByKey: Map<MetadataSchemaKey, MetadataSchema>

    init {
        val currentIndex = LinkedHashMap<String, MetadataSchema>()
        val exactIndex = LinkedHashMap<MetadataSchemaKey, MetadataSchema>()
        try {
            currentSchemas.forEach { schema ->
                MetadataSchemaConfiguration.validate(schema)
                if (currentIndex.put(schema.id, schema) != null) {
                    throw MetadataSchemaConfigurationException()
                }
                if (exactIndex.put(MetadataSchemaKey(schema.id, schema.version), schema) != null) {
                    throw MetadataSchemaConfigurationException()
                }
            }
            historicalSchemas.forEach { schema ->
                MetadataSchemaConfiguration.validate(schema)
                if (!currentIndex.containsKey(schema.id)) {
                    throw MetadataSchemaConfigurationException()
                }
                if (exactIndex.put(MetadataSchemaKey(schema.id, schema.version), schema) != null) {
                    throw MetadataSchemaConfigurationException()
                }
            }
        } catch (exception: MetadataSchemaConfigurationException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw MetadataSchemaConfigurationException(exception)
        }
        currentById = currentIndex.toMap()
        schemasByKey = exactIndex.toMap()
    }

    fun findCurrent(schemaId: String): MetadataSchema? = currentById[schemaId]

    fun findExact(
        schemaId: String,
        version: String,
    ): MetadataSchema? = schemasByKey[MetadataSchemaKey(schemaId, version)]

    override fun resolve(context: MetadataSchemaContext): MetadataSchema? {
        val requestedVersion = context.schemaVersion
        return if (requestedVersion == null) {
            findCurrent(context.schemaId)
        } else {
            findExact(context.schemaId, requestedVersion)
        }
    }
}
