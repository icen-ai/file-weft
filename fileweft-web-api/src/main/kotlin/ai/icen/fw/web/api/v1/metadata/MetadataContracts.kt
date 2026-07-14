package ai.icen.fw.web.api.v1.metadata

import ai.icen.fw.web.api.immutableList
import ai.icen.fw.web.api.optionalText
import ai.icen.fw.web.api.requiredText

/** Public-safe rule description for one metadata field. */
class MetadataFieldDto @JvmOverloads constructor(
    name: String,
    type: String,
    val required: Boolean,
    allowedValues: List<String> = emptyList(),
    val maxLength: Int? = null,
    format: String? = null,
) {
    val name: String = requiredText(name, "Metadata field name", 128)
    val type: String = requiredText(type, "Metadata field type", 32)
    val allowedValues: List<String> = immutableList(allowedValues)
    val format: String? = optionalText(format, "Metadata field format", 512)

    init {
        require(maxLength == null || maxLength > 0) {
            "Metadata field maximum length must be positive when provided."
        }
    }
}

/** Current schema projection returned by the formal v1 metadata endpoint. */
class MetadataSchemaDto(
    id: String,
    version: String,
    fields: List<MetadataFieldDto>,
) {
    val id: String = requiredText(id, "Metadata schema id", 128)
    val version: String = requiredText(version, "Metadata schema version", 128)
    val fields: List<MetadataFieldDto> = immutableList(fields)

    init {
        require(this.fields.map { field -> field.name }.distinct().size == this.fields.size) {
            "Metadata schema field names must be unique."
        }
    }
}
