package ai.icen.fw.web.runtime.v1.metadata

import ai.icen.fw.application.metadata.MetadataSchemaQueryService
import ai.icen.fw.metadata.api.MetadataField
import ai.icen.fw.web.api.v1.metadata.MetadataFieldDto
import ai.icen.fw.web.api.v1.metadata.MetadataSchemaDto

/** Transport-neutral projection of the current metadata schema. */
class MetadataSchemaApiFacade(
    private val schemas: MetadataSchemaQueryService,
) {
    fun get(schemaId: String): MetadataSchemaDto {
        val schema = schemas.findCurrent(schemaId)
        return MetadataSchemaDto(
            id = schema.id,
            version = schema.version,
            fields = schema.fields.map(::toDto),
        )
    }

    private fun toDto(field: MetadataField): MetadataFieldDto = MetadataFieldDto(
        name = field.name,
        type = field.type.name,
        required = field.required,
        allowedValues = field.allowedValues,
        maxLength = field.maxLength,
        format = field.format,
    )
}
