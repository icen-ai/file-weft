package ai.icen.fw.metadata.api

import java.util.Collections
import java.util.LinkedHashMap

/** An immutable, versioned metadata schema. */
class MetadataSchema(
    val id: String,
    val version: String,
    fields: List<MetadataField>,
) {
    val fields: List<MetadataField>
    private val fieldsByName: Map<String, MetadataField>

    init {
        val fieldsSnapshot = immutableList(fields)
        requireContractName(
            id,
            MetadataContractLimits.MAX_SCHEMA_ID_CODE_POINTS,
            "Metadata schema identifier is invalid.",
        )
        requireContractName(
            version,
            MetadataContractLimits.MAX_SCHEMA_VERSION_CODE_POINTS,
            "Metadata schema version is invalid.",
        )
        require(fieldsSnapshot.size <= MetadataContractLimits.MAX_FIELDS_PER_SCHEMA) {
            "Metadata schema contains too many fields."
        }

        val index = LinkedHashMap<String, MetadataField>(fieldsSnapshot.size)
        fieldsSnapshot.forEach { field ->
            require(index.put(field.name, field) == null) {
                "Metadata schema field names must be unique."
            }
        }
        this.fields = fieldsSnapshot
        fieldsByName = Collections.unmodifiableMap(index)
    }

    fun findField(name: String): MetadataField? = fieldsByName[name]
}
