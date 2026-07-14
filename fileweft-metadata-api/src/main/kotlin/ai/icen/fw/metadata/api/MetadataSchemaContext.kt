package ai.icen.fw.metadata.api

/**
 * Trusted lookup context supplied by the application boundary.
 *
 * [tenantId] must come from authenticated tenant context, never directly from
 * a request parameter. A non-null [schemaVersion] requests that exact version;
 * null requests the registry's explicitly configured current schema.
 */
class MetadataSchemaContext @JvmOverloads constructor(
    val tenantId: String,
    val schemaId: String,
    val resourceType: String,
    val operation: String,
    val schemaVersion: String? = null,
) {
    init {
        requireContractName(
            tenantId,
            MetadataContractLimits.MAX_CONTEXT_VALUE_CODE_POINTS,
            "Metadata tenant identifier is invalid.",
        )
        requireContractName(
            schemaId,
            MetadataContractLimits.MAX_SCHEMA_ID_CODE_POINTS,
            "Metadata schema identifier is invalid.",
        )
        requireContractName(
            resourceType,
            MetadataContractLimits.MAX_CONTEXT_VALUE_CODE_POINTS,
            "Metadata resource type is invalid.",
        )
        requireContractName(
            operation,
            MetadataContractLimits.MAX_CONTEXT_VALUE_CODE_POINTS,
            "Metadata operation is invalid.",
        )
        if (schemaVersion != null) {
            requireContractName(
                schemaVersion,
                MetadataContractLimits.MAX_SCHEMA_VERSION_CODE_POINTS,
                "Metadata schema version is invalid.",
            )
        }
    }
}
